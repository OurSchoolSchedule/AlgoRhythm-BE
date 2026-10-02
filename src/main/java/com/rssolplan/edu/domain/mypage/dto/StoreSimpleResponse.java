package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
public class StoreSimpleResponse {
    private Long schoolId;
    private String schoolCode;
    private String name;
    private String address;
    private String phoneNumber;
    private String position;
    private String employmentStatus;
    private LocalDate hireDate;
}
