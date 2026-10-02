package com.rssolplan.edu.domain.mypage.dto;

import lombok.*;

@Getter @Setter @AllArgsConstructor @NoArgsConstructor
public class TeacherProfileUpdateRequest {
    private String username;
    private String email;
}
