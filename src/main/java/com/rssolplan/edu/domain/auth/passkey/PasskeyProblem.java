package com.rssolplan.edu.domain.auth.passkey;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

public class PasskeyProblem extends RuntimeException {
    final HttpStatus status;
    final String code;

    PasskeyProblem(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static PasskeyProblem required() {
        return new PasskeyProblem(HttpStatus.FORBIDDEN, "PASSKEY_REQUIRED", "등록한 패스키로 로그인해 주세요.");
    }

    static PasskeyProblem invalid() {
        return new PasskeyProblem(HttpStatus.BAD_REQUEST, "PASSKEY_INVALID", "패스키 확인에 실패했습니다.");
    }

    static PasskeyProblem stale() {
        return new PasskeyProblem(
                HttpStatus.CONFLICT, "PASSKEY_FLOW_EXPIRED", "인증을 처음부터 다시 진행해 주세요.");
    }

    @RestControllerAdvice
    @org.springframework.core.annotation.Order(0)
    static class Handler {
        record Error(String code, String message) {}

        @ExceptionHandler(PasskeyProblem.class)
        ResponseEntity<Error> handle(PasskeyProblem e) {
            return ResponseEntity.status(e.status).body(new Error(e.code, e.getMessage()));
        }
    }
}
