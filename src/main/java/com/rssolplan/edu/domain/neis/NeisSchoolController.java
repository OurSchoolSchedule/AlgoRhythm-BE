package com.rssolplan.edu.domain.neis;

import com.fasterxml.jackson.databind.JsonNode;
import com.rssolplan.edu.domain.auth.dto.ApiResponse;
import com.rssolplan.edu.global.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/neis/schools")
@RequiredArgsConstructor
class NeisSchoolController {
    private final SchoolLinkReader links;
    private final NeisSchoolService schools;

    @GetMapping("/search")
    SchoolSearch search(@RequestParam String q, @RequestParam(required = false) String officeCode,
                        @RequestParam(required = false) String schoolKind, @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "20") int size) {
        return schools.search(SecurityUtil.getCurrentUserId(), q, officeCode, schoolKind, page, size);
    }

    @GetMapping("/{schoolId}/link")
    SchoolLink link(@PathVariable long schoolId) {
        return links.read(SecurityUtil.getCurrentUserId(), schoolId);
    }

    @PatchMapping("/{schoolId}/link")
    SchoolLink change(@PathVariable long schoolId, @RequestBody JsonNode body) {
        return schools.change(SecurityUtil.getCurrentUserId(), schoolId, LinkChange.from(body));
    }

    @ExceptionHandler(NeisSchoolException.class)
    ResponseEntity<?> schoolError(NeisSchoolException ex) {
        return ResponseEntity.status(ex.status).body(ApiResponse.error(ex.code, ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<?> storageError() {
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error("INTERNAL_ERROR", "학교 연결 정보를 처리할 수 없습니다."));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(ApiResponse.error("BAD_REQUEST", "요청 형식을 확인하세요."));
    }
}
