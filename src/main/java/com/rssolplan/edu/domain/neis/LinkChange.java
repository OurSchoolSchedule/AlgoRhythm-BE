package com.rssolplan.edu.domain.neis;

import com.fasterxml.jackson.databind.JsonNode;

record LinkChange(String officeCode, String standardSchoolCode, long expectedVersion) {
    static LinkChange from(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 3)
            throw new IllegalArgumentException("교육청 코드, 학교 코드, 연결 버전이 필요합니다.");
        JsonNode version = body.path("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0)
            throw new IllegalArgumentException("연결 버전은 0 이상의 정수여야 합니다.");
        return new LinkChange(code(body.path("officeCode")), code(body.path("standardSchoolCode")), version.longValue());
    }

    private static String code(JsonNode value) {
        if (!value.isTextual() || !value.textValue().matches("[a-zA-Z0-9]{1,16}"))
            throw new IllegalArgumentException("올바른 학교 코드를 입력하세요.");
        return value.textValue();
    }
}
