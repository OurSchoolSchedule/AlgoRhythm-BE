package com.rssolplan.edu.domain.schedule.workavailability.dto;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class TeacherAvailabilityRequestDto {
    private List<AvailabilityItem> unavailabilities;

    @Getter
    @NoArgsConstructor
    public static class AvailabilityItem {
        private DayOfWeek dayOfWeek;
        private int periodNumber;
        private String reason;
    }
}
