package com.rssolplan.edu.domain.schedule.loadimport;

import static org.assertj.core.api.Assertions.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

class LoadWorkbookParserTest {
    private final LoadWorkbookParser parser = new LoadWorkbookParser();

    static LoadCatalog catalog() {
        return new LoadCatalog(
                new LoadCatalog.Names(List.of(new LoadCatalog.Name(10, "김교사"))),
                new LoadCatalog.Names(
                        List.of(new LoadCatalog.Name(20, "공통수학I"), new LoadCatalog.Name(21, "국어"))),
                Map.of(new LoadCatalog.ClassKey(1, 1), 31L, new LoadCatalog.ClassKey(1, 2), 32L));
    }

    static void row(Sheet s, int r, String... cells) {
        Row row = s.createRow(r);
        for (int c = 0; c < cells.length; c++) row.createCell(c).setCellValue(cells[c]);
    }

    static byte[] file(Consumer<Sheet> configure) {
        try (XSSFWorkbook book = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = book.createSheet("시수표");
            row(s, 0, "교사명", "정식과목명", "단축과목명", "1학년", "", "계");
            s.addMergedRegion(new CellRangeAddress(0, 0, 3, 4));
            row(s, 1, "", "", "", "1반", "2반", "");
            row(s, 2, "김 교사", "공통 수학Ⅰ", "공수", "3", "2", "5");
            configure.accept(s);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void mergedHeaderNormalizedNamesAndFormulaSum() {
        byte[] data =
                file(
                        s -> {
                            Cell sum = s.getRow(2).getCell(5);
                            sum.setCellFormula("SUM(D3:E3)");
                            s.getRow(2).getCell(3).setCellValue(3);
                            s.getRow(2).getCell(4).setCellValue(2);
                            s.getWorkbook()
                                    .getCreationHelper()
                                    .createFormulaEvaluator()
                                    .evaluateFormulaCell(sum);
                        });
        var result = parser.parse(data, null, catalog());
        assertThat(result.totalPeriods()).isEqualTo(5);
        assertThat(result.demands())
                .containsExactly(
                        new LoadWorkbookParser.Demand("10", "20", "31", 3, "D3"),
                        new LoadWorkbookParser.Demand("10", "20", "32", 2, "E3"));
    }

    @Test
    void flatHeaderBlankAndNumericZero() {
        byte[] data =
                file(
                        s -> {
                            s.removeMergedRegion(0);
                            row(s, 0, "교사", "과목", "약칭", "1-1", "1학년 2반", "합계");
                            s.removeRow(s.getRow(1));
                            row(s, 2, "김교사", "공통수학Ⅰ", "", "", "2", "2");
                            row(s, 3, "김교사", "국어", "", "1", "0", "1");
                        });
        var out = parser.parse(data, "시수표", catalog());
        assertThat(out.totalPeriods()).isEqualTo(3);
        assertThat(out.demands()).hasSize(2);
    }

    @Test
    void verticalTeacherMergeUsesOnlyAnchor() {
        byte[] data =
                file(
                        s -> {
                            row(s, 3, "", "국어", "", "1", "0", "1");
                            s.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
                        });
        assertThat(parser.parse(data, null, catalog()).totalPeriods()).isEqualTo(6);
    }

    @Test
    void explicitAndShortSubjectNamesResolveWithoutGuessing() {
        LoadCatalog c = catalog();
        c.subjects().addAliases(Map.of("공수", 20L), Set.of(20L, 21L));
        var bytes = file(s -> s.getRow(2).getCell(1).setCellValue("학교 자체 표기"));
        assertThat(parser.parse(bytes, null, c).demands())
                .extracting(LoadWorkbookParser.Demand::subjectId)
                .containsOnly("20");
        assertThatThrownBy(() -> c.subjects().addAliases(Map.of("국어", 20L), Set.of(20L, 21L)))
                .isInstanceOf(LoadProblem.class);
        assertThatThrownBy(() -> c.subjects().addAliases(Map.of("공수", 99L), Set.of(20L, 21L)))
                .isInstanceOf(LoadProblem.class);
    }

    private LoadProblem issue(byte[] bytes) {
        return catchThrowableOfType(LoadProblem.class, () -> parser.parse(bytes, "시수표", catalog()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "4/1", "999999999999999999999999"})
    void invalidHoursHaveExactCell(String value) {
        var ex = issue(file(s -> s.getRow(2).getCell(3).setCellValue(value)));
        assertThat(ex.fieldErrors)
                .anyMatch(
                        e ->
                                e.code().equals("INVALID_HOURS")
                                        && e.cell().equals("D3")
                                        && e.row() == 3
                                        && e.column().equals("D"));
    }

    @Test
    void issuesAccumulateAndNoPartialResult() {
        var ex =
                issue(
                        file(
                                s -> {
                                    s.getRow(2).getCell(5).setCellValue(7);
                                    row(s, 3, "김교사", "공통수학I", "", "2", "0", "2");
                                    row(s, 4, "외부교사", "알수없는과목", "", "1", "", "1");
                                }));
        assertThat(ex.fieldErrors)
                .extracting(LoadProblem.Issue::cell)
                .containsExactly("F3", "D4", "A5", "B5");
        assertThat(ex.fieldErrors)
                .extracting(LoadProblem.Issue::code)
                .contains(
                        "ROW_TOTAL_MISMATCH",
                        "DUPLICATE_DEMAND",
                        "UNKNOWN_TEACHER",
                        "UNKNOWN_SUBJECT");
    }

    @Test
    void missingCachedFormulaAndMergedHoursAreRejected() {
        assertThat(
                        issue(
                                        file(
                                                s -> {
                                                    var cell =
                                                            (org.apache.poi.xssf.usermodel.XSSFCell)
                                                                    s.getRow(2).getCell(3);
                                                    cell.setCellFormula("1+2");
                                                    if (cell.getCTCell().isSetV())
                                                        cell.getCTCell().unsetV();
                                                }))
                                .fieldErrors)
                .anyMatch(i -> i.cell().equals("D3") && i.code().equals("INVALID_HOURS"));
        assertThat(
                        issue(file(s -> s.addMergedRegion(new CellRangeAddress(2, 2, 3, 4))))
                                .fieldErrors)
                .anyMatch(i -> i.code().equals("MERGED_HOURS"));
    }

    @Test
    void numericExcelDateIsNotWeeklyHours() {
        byte[] bytes =
                file(
                        s -> {
                            Cell c = s.getRow(2).getCell(3);
                            c.setCellValue(java.time.LocalDate.of(2026, 11, 20));
                            var style = s.getWorkbook().createCellStyle();
                            style.setDataFormat((short) 14);
                            c.setCellStyle(style);
                        });
        assertThat(issue(bytes).fieldErrors)
                .anyMatch(i -> i.cell().equals("D3") && i.code().equals("INVALID_HOURS"));
    }

    @Test
    void labelsCanBeOnLowerHeaderRow() {
        byte[] bytes =
                file(
                        s -> {
                            for (int c : new int[] {0, 1, 2, 5}) {
                                s.getRow(1)
                                        .getCell(c)
                                        .setCellValue(s.getRow(0).getCell(c).getStringCellValue());
                                s.getRow(0).getCell(c).setBlank();
                            }
                        });
        assertThat(parser.parse(bytes, null, catalog()).totalPeriods()).isEqualTo(5);
    }

    @Test
    void sameTeacherNameIsAmbiguous() {
        LoadCatalog c = catalog();
        var names =
                new LoadCatalog.Names(
                        List.of(new LoadCatalog.Name(10, "김교사"), new LoadCatalog.Name(11, "김 교사")));
        var ex =
                catchThrowableOfType(
                        LoadProblem.class,
                        () ->
                                parser.parse(
                                        file(s -> {}),
                                        null,
                                        new LoadCatalog(names, c.subjects(), c.classes())));
        assertThat(ex.fieldErrors)
                .anyMatch(i -> i.code().equals("AMBIGUOUS_TEACHER") && i.cell().equals("A3"));
    }

    @Test
    void unknownOrMissingClassHeaderIsLocated() {
        assertThat(
                        issue(file(s -> s.getRow(1).getCell(4).setCellValue("9반")))
                                .fieldErrors
                                .get(0)
                                .cell())
                .isEqualTo("E2");
        assertThat(issue(file(s -> s.getRow(1).getCell(4).setBlank())).fieldErrors.get(0).code())
                .isEqualTo("CLASS_HEADER");
    }

    @Test
    void sheetSelectionAndFileErrors() {
        byte[] bytes = file(s -> s.getWorkbook().createSheet("기타"));
        assertThat(
                        catchThrowableOfType(
                                        LoadProblem.class,
                                        () -> parser.parse(bytes, null, catalog()))
                                .code)
                .isEqualTo("SELECT_SHEET");
        assertThat(parser.parse(bytes, "시수표", catalog()).dataRows()).isEqualTo(1);
        assertThat(issue(new byte[] {1, 2, 3}).status.value()).isEqualTo(415);
    }
}
