package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
public class OwnerStoreResponse {
    private Long schoolId;
    private String schoolCode;
    private String name;
    private String address;
    private String phoneNumber;
}
