package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

@Getter @Setter @AllArgsConstructor @NoArgsConstructor
public class OwnerSchoolUpdateRequest {
    private String name;
    private String address;
    private String phoneNumber;
}
