package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter @AllArgsConstructor @NoArgsConstructor
public class TeacherJoinSchoolRequest {
    private String schoolCode;
    private LocalDate hireDate;
    private String nameConfirmation;
    private String subjectHint;
    private String displayName;
}
