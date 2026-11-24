package com.rssolplan.edu.domain.schedule.loadimport;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.springframework.http.HttpStatus;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads one worksheet without instantiating a workbook or evaluating formulas. */
final class LoadSheet extends DefaultHandler {
    static final int FILE_LIMIT = 5 * 1024 * 1024;
    static final int MAX_ROWS = 5030, MAX_COLS = 256, MAX_CELLS = 100_000;
    private static final int MAX_EXPANDED = 50 * 1024 * 1024;

    record Value(String text, boolean invalid) {}

    static final Value EMPTY = new Value("", false);
    final String name;
    final SortedMap<Integer, Map<Integer, Value>> rows = new TreeMap<>();
    final Map<CellAddress, CellAddress> merged = new HashMap<>();
    private final SharedStrings shared;
    private final StylesTable styles;
    private final StringBuilder content = new StringBuilder();
    private CellAddress address;
    private String type;
    private boolean capture, formula, cached, dateStyle;
    private int cells;

    private LoadSheet(String name, SharedStrings shared, StylesTable styles) {
        this.name = name;
        this.shared = shared;
        this.styles = styles;
    }

    static LoadSheet read(byte[] bytes, String wanted) {
        if (bytes.length > FILE_LIMIT) throw limit();
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K')
            throw LoadProblem.file(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "XLSX_REQUIRED", ".xlsx 파일을 선택하세요.");
        try {
            inspectArchive(bytes);
            try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(bytes))) {
                if (pkg.getPartsByContentType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")
                        .isEmpty())
                    throw LoadProblem.file(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "XLSX_REQUIRED",
                            "일반 .xlsx 통합 문서가 필요합니다.");
                XSSFReader reader = new XSSFReader(pkg);
                reader.setUseReadOnlySharedStringsTable(true);
                XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
                List<String> names = new ArrayList<>();
                org.apache.poi.openxml4j.opc.PackagePart selected = null;
                String selectedName = null;
                while (it.hasNext()) {
                    try (InputStream in = it.next()) {
                        if (!it.getSheetPart().getContentType().endsWith("worksheet+xml")) continue;
                        String name = it.getSheetName();
                        names.add(name);
                        if (wanted == null ? names.size() != 1 : !wanted.equals(name)) continue;
                        selected = it.getSheetPart();
                        selectedName = name;
                    }
                }
                if (selected == null || (wanted == null && names.size() != 1))
                    throw LoadProblem.file(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "SELECT_SHEET",
                            "sheet 파라미터로 시트를 선택하세요. 사용 가능: " + String.join(", ", names));
                LoadSheet data =
                        new LoadSheet(
                                selectedName,
                                reader.getSharedStringsTable(),
                                reader.getStylesTable());
                try (InputStream in = selected.getInputStream()) {
                    XMLReader xml = XMLHelper.newXMLReader();
                    xml.setContentHandler(data);
                    xml.parse(new InputSource(in));
                }
                return data;
            }
        } catch (LoadProblem e) {
            throw e;
        } catch (Exception e) {
            throw LoadProblem.file(
                    HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_XLSX", "통합 문서 형식을 확인하고 다시 저장하세요.");
        }
    }

    private static void inspectArchive(byte[] bytes) throws IOException {
        long expanded = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++entries > 1500) throw limit();
                String path = entry.getName().toLowerCase(Locale.ROOT);
                if (path.endsWith("vbaproject.bin") || path.startsWith("xl/externallinks/"))
                    throw LoadProblem.file(
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "UNSUPPORTED_WORKBOOK",
                            "매크로와 외부 참조를 제거하세요.");
                for (int n; (n = zip.read(buffer)) != -1; ) {
                    expanded += n;
                    if (expanded > MAX_EXPANDED) throw limit();
                }
            }
        }
    }

    private static LoadProblem limit() {
        return LoadProblem.file(
                HttpStatus.PAYLOAD_TOO_LARGE, "WORKBOOK_LIMIT", "파일 또는 시트가 허용 크기를 초과했습니다.");
    }

    Value raw(int r, int c) {
        return rows.getOrDefault(r, Map.of()).getOrDefault(c, EMPTY);
    }

    Value at(int r, int c) {
        CellAddress a = merged.get(new CellAddress(r, c));
        return a == null ? raw(r, c) : raw(a.getRow(), a.getColumn());
    }

    @Override
    public void startElement(String uri, String tag, String qName, Attributes attrs) {
        switch (tag) {
            case "c" -> {
                address = new CellAddress(attrs.getValue("r"));
                if (++cells > MAX_CELLS
                        || address.getRow() >= MAX_ROWS
                        || address.getColumn() >= MAX_COLS) throw limit();
                type = attrs.getValue("t");
                String index = attrs.getValue("s");
                dateStyle = false;
                if (index != null && styles != null && (type == null || type.equals("n"))) {
                    var style = styles.getStyleAt(Integer.parseInt(index));
                    dateStyle =
                            DateUtil.isADateFormat(
                                    style.getDataFormat(), style.getDataFormatString());
                }
                content.setLength(0);
                capture = formula = cached = false;
            }
            case "f" -> formula = true;
            case "v" -> {
                capture = true;
                cached = true;
            }
            case "t" -> capture = address != null && "inlineStr".equals(type);
            case "mergeCell" -> addMerge(CellRangeAddress.valueOf(attrs.getValue("ref")));
            default -> {}
        }
    }

    private void addMerge(CellRangeAddress range) {
        int r0 = range.getFirstRow(), c0 = range.getFirstColumn();
        if (range.getLastRow() >= MAX_ROWS
                || range.getLastColumn() >= MAX_COLS
                || (long) merged.size() + range.getNumberOfCells() > MAX_CELLS) throw limit();
        CellAddress first = new CellAddress(r0, c0);
        for (int r = r0; r <= range.getLastRow(); r++) {
            for (int c = c0; c <= range.getLastColumn(); c++) {
                if (merged.put(new CellAddress(r, c), first) != null)
                    throw LoadProblem.invalid(
                            List.of(
                                    LoadProblem.Issue.at(
                                            "OVERLAPPING_MERGE", name, r, c, "병합 셀이 겹칩니다.")));
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (capture) content.append(ch, start, length);
    }

    @Override
    public void endElement(String uri, String tag, String qName) {
        if (tag.equals("v") || tag.equals("t")) capture = false;
        if (!tag.equals("c")) return;
        String text = content.toString();
        boolean invalid =
                "e".equals(type)
                        || "b".equals(type)
                        || "d".equals(type)
                        || (dateStyle && !text.isBlank())
                        || (formula && (!cached || text.isBlank()));
        if ("s".equals(type) && !text.isBlank())
            text = shared.getItemAt(Integer.parseInt(text)).getString();
        if (!text.isBlank() || invalid) {
            rows.computeIfAbsent(address.getRow(), n -> new TreeMap<>())
                    .put(address.getColumn(), new Value(text, invalid));
        }
        address = null;
    }
}
