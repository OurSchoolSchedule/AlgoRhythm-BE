package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
public class OwnerProfileResponse {
    private Long userId;
    private String username;
    private String email;
    private String profileImageUrl;
    private String position;
    private String employmentStatus;
}
