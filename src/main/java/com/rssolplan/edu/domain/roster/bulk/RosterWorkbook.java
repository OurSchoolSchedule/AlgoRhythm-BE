package com.rssolplan.edu.domain.roster.bulk;

import com.rssolplan.edu.domain.schedule.loadimport.LoadProblem.Issue;
import com.rssolplan.edu.domain.schedule.loadimport.LoadSheet;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

final class RosterWorkbook {
    static final String SHEET = "교사명부";
    static final List<String> HEADER = List.of("인증 이메일", "표시 이름", "과목 코드", "담임 학급 코드");
    record RowInput(int row, String email, String name, String subject, String homeroom) {}
    record Input(List<RowInput> rows, List<Issue> errors) {}
    record Code(String type, long id, String label) {}

    static Input parse(byte[] bytes) {
        var cells = LoadSheet.readTable(bytes, SHEET);
        var errors = new ArrayList<Issue>();
        var rows = new ArrayList<RowInput>();
        var header = cells.getOrDefault(0, Map.of());
        for (int col = 0; col < HEADER.size(); col++) {
            if (!HEADER.get(col).equals(value(header, col)) || header.get(col).invalid()) {
                errors.add(issue(1, col, "INVALID_HEADER", "머리글은 '" + HEADER.get(col) + "'이어야 합니다."));
            }
        }
        cells.forEach((index, row) -> {
            row.forEach((col, cell) -> {
                if (col >= HEADER.size()) errors.add(issue(index + 1, col, "EXTRA_COLUMN", "양식에 없는 열입니다."));
                else if (index > 0 && cell.invalid()) errors.add(issue(index + 1, col, "INVALID_CELL", "수식·날짜·오류 값 대신 값을 입력하세요."));
            });
            if (index > 0) rows.add(new RowInput(index + 1, value(row, 0), value(row, 1), value(row, 2), value(row, 3)));
        });
        if (rows.isEmpty()) errors.add(issue(2, 0, "EMPTY_ROSTER", "한 명 이상의 교사를 입력하세요."));
        return new Input(rows, errors);
    }

    private static String value(Map<Integer, LoadSheet.Value> row, int col) {
        var cell = row.get(col);
        return cell == null ? "" : cell.text().strip();
    }

    static Issue issue(int row, int column, String code, String message) {
        return Issue.at(code, SHEET, row - 1, column, message);
    }

    static byte[] template(List<Code> codes) throws IOException {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            header.setFont(font);
            var sheet = workbook.createSheet(SHEET);
            headings(sheet, HEADER, header);
            var text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            for (int i = 0; i < HEADER.size(); i++) {
                sheet.setDefaultColumnStyle(i, text);
                sheet.setColumnWidth(i, (i == 0 ? 36 : 23) * 256);
            }
            var reference = workbook.createSheet("코드표");
            headings(reference, List.of("구분", "코드", "이름"), header);
            for (Code code : codes) {
                Row row = reference.createRow(reference.getLastRowNum() + 1);
                row.createCell(0).setCellValue(code.type());
                row.createCell(1).setCellValue(Long.toString(code.id()));
                row.createCell(2).setCellValue(code.label());
            }
            reference.setColumnWidth(0, 15 * 256);
            reference.setColumnWidth(1, 24 * 256);
            reference.setColumnWidth(2, 36 * 256);
            workbook.write(bytes);
            return bytes.toByteArray();
        }
    }

    private static void headings(Sheet sheet, List<String> titles, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < titles.size(); i++) {
            var cell = row.createCell(i);
            cell.setCellValue(titles.get(i));
            cell.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }
}
