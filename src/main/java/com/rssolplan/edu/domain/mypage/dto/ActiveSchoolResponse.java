package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
public class ActiveSchoolResponse {
    private Long schoolId;
    private String schoolCode;
    private String name;
    private String address;
    private String phoneNumber;
    private String position;
    private String employmentStatus;
}
