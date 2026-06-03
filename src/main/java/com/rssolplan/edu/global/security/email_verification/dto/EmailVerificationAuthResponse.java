package com.rssolplan.edu.global.security.email_verification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationAuthResponse {

    private boolean success;
    private String message;
    private String accessToken;
    private String refreshToken;
    private Long userId;
    private boolean isNewUser;
}

