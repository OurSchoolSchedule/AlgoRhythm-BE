package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

@Getter @Setter @Builder
@AllArgsConstructor @NoArgsConstructor
public class StaffProfileResponse {
    private Long userId;
    private String username;
    private String email;
    private String profileImageUrl;
    private String position;
    private String employmentStatus;
    private CurrentSchool currentSchool;

    @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
    public static class CurrentSchool {
        private Long schoolId;
        private String name;
        private String schoolCode;
    }
}
