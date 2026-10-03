package com.rssolplan.edu.domain.schedule.workavailability.dto;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.workavailability.TeacherAvailability;
import lombok.Getter;

@Getter
public class TeacherAvailabilityResponseDto {
    private final Long id;
    private final Long schoolUserId;
    private final DayOfWeek dayOfWeek;
    private final int periodNumber;
    private final String reason;

    public TeacherAvailabilityResponseDto(TeacherAvailability a) {
        this.id = a.getId();
        this.schoolUserId = a.getSchoolUser().getId();
        this.dayOfWeek = a.getDayOfWeek();
        this.periodNumber = a.getPeriodNumber();
        this.reason = a.getReason();
    }
}
