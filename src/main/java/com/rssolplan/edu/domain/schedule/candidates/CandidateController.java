package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import com.rssolplan.edu.global.exception.BadRequestException;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class CandidateController {
    private final CandidateInputService inputs;
    private final CandidateRunner runner;

    @PostMapping("/terms/{termId}/schedule-candidates")
    public Response term(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long termId,
            @RequestBody Request request) {
        return generate(userId, termId, null, request);
    }

    @PostMapping("/school/schedule-candidates")
    public Response school(
            @AuthenticationPrincipal Long userId,
            @RequestParam Integer academicYear,
            @RequestBody Request request) {
        return generate(userId, null, academicYear, request);
    }

    private Response generate(Long userId, Long termId, Integer year, Request request) {
        if (request.validatedLoadId() == null)
            throw new BadRequestException("마일스톤 1 검증 응답의 validatedLoadId가 필요합니다.");
        return runner.generate(
                request.validatedLoadId(), () -> inputs.load(userId, termId, year, request));
    }

    @ExceptionHandler(RejectedExecutionException.class)
    ResponseEntity<?> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "2")
                .body(Map.of("code", "GENERATION_BUSY", "message", "잠시 후 다시 요청하세요."));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    ResponseEntity<?> invalidParameter() {
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "code",
                                "INVALID_PARAMETER",
                                "message",
                                "학기·학년도, UUID와 교시 형식을 확인하세요."));
    }
}
