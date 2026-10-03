package com.rssolplan.edu.domain.onboarding.dto;

import lombok.Getter;

import java.time.LocalDate;

@Getter
public class OnboardingRequest {
    private String role;        // "ADMIN" or "TEACHER"
    private String schoolCode;  // TEACHER일 경우 참여할 학교 코드
    private String name;        // ADMIN일 경우 새 학교 이름
    private String address;
    private String phoneNumber;
    private LocalDate hireDate;
}
