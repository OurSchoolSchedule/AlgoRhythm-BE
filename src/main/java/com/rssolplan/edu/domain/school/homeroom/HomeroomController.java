package com.rssolplan.edu.domain.school.homeroom;

import com.rssolplan.edu.domain.schedule.loadimport.LoadProblem;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v2/terms/{termId}/homerooms")
@RequiredArgsConstructor
public class HomeroomController {
    private final HomeroomService service;

    @GetMapping
    public List<HomeroomService.RoomAssignment> list(@AuthenticationPrincipal Long user, @PathVariable long termId) {
        return service.list(user, termId);
    }

    @PostMapping(path="/import", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<HomeroomService.RoomAssignment> apply(@AuthenticationPrincipal Long user, @PathVariable long termId,
                                                      @RequestPart("file") MultipartFile file) throws IOException {
        return service.apply(user, termId, file.getBytes());
    }

    public record ErrorBody(String code, String message, List<LoadProblem.Issue> fieldErrors) {}

    @ExceptionHandler(HomeroomWorkbook.Invalid.class)
    public ResponseEntity<ErrorBody> invalid(HomeroomWorkbook.Invalid e) {
        return ResponseEntity.unprocessableEntity().body(new ErrorBody("INVALID_CLASS_ROLES", e.getMessage(), e.issues));
    }

    @ExceptionHandler(LoadProblem.class)
    public ResponseEntity<ErrorBody> workbook(LoadProblem e) {
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(), e.getMessage(), e.issues()));
    }
}
