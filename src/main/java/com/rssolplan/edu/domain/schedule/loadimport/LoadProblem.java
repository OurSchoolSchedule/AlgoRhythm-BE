package com.rssolplan.edu.domain.schedule.loadimport;

import org.apache.poi.ss.util.CellReference;
import org.springframework.http.HttpStatus;

import java.util.List;

public class LoadProblem extends RuntimeException {
    public record Issue(
            String code, String sheet, Integer row, String column, String cell, String message) {
        public static Issue at(String code, String sheet, int row, int col, String message) {
            String column = CellReference.convertNumToColString(col);
            return new Issue(code, sheet, row + 1, column, column + (row + 1), message);
        }
    }

    final HttpStatus status;
    final String code;
    final List<Issue> fieldErrors;

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<Issue> issues() { return fieldErrors; }

    LoadProblem(HttpStatus status, String code, String message, List<Issue> issues) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = List.copyOf(issues);
    }

    static LoadProblem file(HttpStatus status, String code, String message) {
        return new LoadProblem(status, code, message, List.of());
    }

    static LoadProblem invalid(List<Issue> issues) {
        return new LoadProblem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_LOAD_SHEET",
                "시수표를 수정한 후 다시 업로드하세요.",
                issues);
    }
}
