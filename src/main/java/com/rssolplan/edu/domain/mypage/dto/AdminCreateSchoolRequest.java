package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

import java.time.LocalDate;

@Getter @Setter @AllArgsConstructor @NoArgsConstructor
public class AdminCreateSchoolRequest {
    private String name;
    private String address;
    private String phoneNumber;
    private LocalDate hireDate;
}
