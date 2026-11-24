package com.rssolplan.edu.domain.schedule.operations;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
        basePackages = {
            "com.rssolplan.edu.domain.schedule.candidates",
            "com.rssolplan.edu.domain.schedule.operations"
        })
class ScheduleParameterErrors {
    @ExceptionHandler({
        org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid() {
        return Map.of("code", "INVALID_PARAMETER", "message", "요청 필드, 버전, 날짜, UUID 형식을 확인하세요.");
    }
}
