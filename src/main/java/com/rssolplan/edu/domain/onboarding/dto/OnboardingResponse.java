package com.rssolplan.edu.domain.onboarding.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class OnboardingResponse {
    private Long userId;
    private Long schoolUserId;
    private Long schoolId;
    private String position;
    private String employmentStatus;
    private String schoolCode;
    private String schoolName;
    private String address;
    private String phoneNumber;
    private LocalDate hireDate;
}
