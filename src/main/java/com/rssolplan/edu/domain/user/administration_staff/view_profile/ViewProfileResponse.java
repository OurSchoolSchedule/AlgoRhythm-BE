package com.rssolplan.edu.domain.user.administration_staff.view_profile;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class ViewProfileResponse {

    private String username;
    private String profileImageUrl;
    private String status;
    private String position;
    private String schoolName;
    private String email;
    private LocalDate hireDate;
    private long daysWorked;
}
