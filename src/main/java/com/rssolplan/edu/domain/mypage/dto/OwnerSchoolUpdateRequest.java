package com.rssolplan.edu.domain.mypage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @AllArgsConstructor @NoArgsConstructor
public class OwnerSchoolUpdateRequest {
    private String name;
    private String address;
    private String phoneNumber;
}
