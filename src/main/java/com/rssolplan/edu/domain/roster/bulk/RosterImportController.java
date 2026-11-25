package com.rssolplan.edu.domain.roster.bulk;

import com.rssolplan.edu.domain.auth.dto.ApiResponse;
import com.rssolplan.edu.domain.schedule.loadimport.LoadProblem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/schools/{schoolId}/roster/terms/{termId}/imports")
@RequiredArgsConstructor
public class RosterImportController {
    private final RosterImportService service;

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@AuthenticationPrincipal Long user, @PathVariable long schoolId,
                                            @PathVariable long termId) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"teacher-roster.xlsx\"")
                .cacheControl(CacheControl.noStore()).body(service.template(user, schoolId, termId));
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RosterImportService.Preview> preview(@AuthenticationPrincipal Long user,
            @PathVariable long schoolId, @PathVariable long termId, @RequestPart("file") MultipartFile file) throws IOException {
        var result = service.preview(user, schoolId, termId, file.getBytes());
        return ResponseEntity.status(result.errors().isEmpty() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(result);
    }

    public record Confirmation(@NotNull @Pattern(regexp = "[a-f0-9]{64}") String fileSha256) {}

    @PostMapping("/{id}/confirm")
    public RosterImportService.Receipt confirm(@AuthenticationPrincipal Long user, @PathVariable long schoolId,
            @PathVariable long termId, @PathVariable UUID id, @Valid @RequestBody Confirmation confirmation) {
        return service.confirm(user, schoolId, termId, id, confirmation.fileSha256());
    }

    public record WorkbookError(String code, String message, List<LoadProblem.Issue> errors) {}
    @ExceptionHandler(LoadProblem.class)
    public ResponseEntity<WorkbookError> workbook(LoadProblem ex) {
        String code = ex.issues().isEmpty() ? ex.code() : "INVALID_ROSTER_SHEET";
        String message = switch (code) {
            case "SELECT_SHEET" -> "내려받은 양식의 '교사명부' 시트 이름을 확인하세요.";
            case "INVALID_ROSTER_SHEET" -> "교사명부 시트의 표시된 셀을 수정하세요.";
            default -> ex.getMessage();
        };
        return ResponseEntity.status(ex.status()).body(new WorkbookError(code, message, ex.issues()));
    }

    @ExceptionHandler(RosterImportService.ImportConflict.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Void> conflict(RosterImportService.ImportConflict ex) {
        return ApiResponse.error(ex.code, ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalidInput() {
        return ApiResponse.error("BAD_REQUEST", "파일, 경로 ID 또는 SHA-256 형식을 확인하세요.");
    }
}
