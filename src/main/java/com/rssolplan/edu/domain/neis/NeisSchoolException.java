package com.rssolplan.edu.domain.neis;

import org.springframework.http.HttpStatus;

class NeisSchoolException extends RuntimeException {
    final HttpStatus status;
    final String code;

    NeisSchoolException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static NeisSchoolException conflict() {
        return new NeisSchoolException(HttpStatus.CONFLICT, "NEIS_LINK_CHANGED", "학교 연결이 변경되었습니다. 다시 조회하세요.");
    }
}
