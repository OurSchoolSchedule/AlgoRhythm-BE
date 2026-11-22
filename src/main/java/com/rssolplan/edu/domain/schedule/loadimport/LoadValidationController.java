package com.rssolplan.edu.domain.schedule.loadimport;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2")
public class LoadValidationController {
    private final LoadValidationService service;
    private final LoadPreviewStore previews;
    private final LoadWorkbookParser parser = new LoadWorkbookParser();

    @PostMapping(
            path = "/terms/{termId}/load-imports/validate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LoadWorkbookParser.Result validateTerm(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long termId,
            @RequestPart(required = false) MultipartFile file,
            @RequestParam(required = false) String sheet,
            @RequestParam(defaultValue = "false") boolean forGeneration,
            @RequestPart(required = false) Map<String, Long> subjectAliases)
            throws IOException {
        return validate(
                file,
                sheet,
                forGeneration,
                termId,
                service.catalog(userId, termId, null, aliases(subjectAliases)));
    }

    // A school can validate its first upload before it has ever published a timetable_set.
    @PostMapping(
            path = "/school/load-imports/validate",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LoadWorkbookParser.Result validateYear(
            @AuthenticationPrincipal Long userId,
            @RequestParam Integer academicYear,
            @RequestPart(required = false) MultipartFile file,
            @RequestParam(required = false) String sheet,
            @RequestParam(defaultValue = "false") boolean forGeneration,
            @RequestPart(required = false) Map<String, Long> subjectAliases)
            throws IOException {
        return validate(
                file,
                sheet,
                forGeneration,
                null,
                service.catalog(userId, null, academicYear, aliases(subjectAliases)));
    }

    private Map<String, Long> aliases(Map<String, Long> aliases) {
        return aliases == null ? Map.of() : aliases;
    }

    private LoadWorkbookParser.Result validate(
            MultipartFile file,
            String sheet,
            boolean forGeneration,
            Long termId,
            LoadCatalog catalog)
            throws IOException {
        if (file == null || file.isEmpty())
            throw LoadProblem.file(HttpStatus.BAD_REQUEST, "MISSING_FILE", "엑셀 파일을 첨부하세요.");
        if (file.getSize() > LoadSheet.FILE_LIMIT)
            throw LoadProblem.file(
                    HttpStatus.PAYLOAD_TOO_LARGE, "WORKBOOK_LIMIT", "파일은 5 MiB까지 허용됩니다.");
        var result = parser.parse(file.getBytes(), sheet, catalog);
        return forGeneration ? previews.retain(catalog, termId, result) : result;
    }

    record ErrorBody(String code, String message, List<LoadProblem.Issue> fieldErrors) {}

    @ExceptionHandler(LoadProblem.class)
    ResponseEntity<ErrorBody> problem(LoadProblem e) {
        return ResponseEntity.status(e.status)
                .body(new ErrorBody(e.code, e.getMessage(), e.fieldErrors));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ErrorBody> badParameter(Exception e) {
        return ResponseEntity.badRequest()
                .body(
                        new ErrorBody(
                                "INVALID_PARAMETER", "학년도·학기 ID 또는 별칭 JSON 형식을 확인하세요.", List.of()));
    }
}
