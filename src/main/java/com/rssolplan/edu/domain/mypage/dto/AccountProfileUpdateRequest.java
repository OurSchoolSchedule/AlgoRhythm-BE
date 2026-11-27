package com.rssolplan.edu.domain.mypage.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Older clients may still echo the contact email when saving the account name. */
@JsonIgnoreProperties({"email", "verifiedEmail"})
public record AccountProfileUpdateRequest(String username) {
    public String normalizedName() {
        String name = username == null ? "" : username.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 100)
            throw new IllegalArgumentException("계정 이름은 1~100자로 입력해 주세요.");
        return name;
    }
}
