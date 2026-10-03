package com.rssolplan.edu.global.exception;

import com.rssolplan.edu.domain.auth.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 400 Bad Request - 잘못된 시간 범위 등
    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleBadRequest(BadRequestException ex) {
        return ApiResponse.error("BAD_REQUEST", ex.getMessage());
    }

    // 401 Unauthorized - JWT 토큰 문제
    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Object> handleUnauthorized(UnauthorizedException ex) {
        return ApiResponse.error("UNAUTHORIZED", ex.getMessage());
    }

    //403 Forbidden , 인가실패
    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Object> handleForbidden(ForbiddenException ex) {
        return ApiResponse.error("FORBIDDEN", ex.getMessage());
    }

    // 404 Not founded, 리소스 찾을 수 없음.
    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Object> handleNotFound(NotFoundException ex) {
        return ApiResponse.error("NOT_FOUND", ex.getMessage());
    }

    // 401 Draft 만료/불일치
    @ExceptionHandler(DraftExpiredException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Object> handleDraftExpired(DraftExpiredException ex) {
        return ApiResponse.error("DRAFT_NOT_FOUND_OR_EXPIRED", ex.getMessage());
    }

    // 422 AI 파싱 실패
    @ExceptionHandler(IntentParseException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<Object> handleIntentParse(IntentParseException ex) {
        return ApiResponse.error("INTENT_PARSE_FAILED", ex.getMessage());
    }

    // 403 Forbidden - 학교 간 리소스 접근, Java SecurityException
    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Object> handleSecurity(SecurityException ex) {
        return ApiResponse.error("FORBIDDEN", ex.getMessage());
    }

    // 409 Conflict - 이미 처리된 상태, 중복 처리 시도
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<Object> handleIllegalState(IllegalStateException ex) {
        return ApiResponse.error("CONFLICT", ex.getMessage());
    }

    // 400 Bad Request - 잘못된 파라미터/액션 값
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.error("BAD_REQUEST", ex.getMessage());
    }

    // fallback
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Object> handleGeneralException(Exception ex) {
        return ApiResponse.error("INTERNAL_ERROR", ex.getMessage());
    }
}
