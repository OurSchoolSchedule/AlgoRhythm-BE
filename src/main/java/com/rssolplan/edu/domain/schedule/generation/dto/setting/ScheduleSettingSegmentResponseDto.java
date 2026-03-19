package com.rssolplan.edu.domain.schedule.generation.dto.setting;

import lombok.*;

import java.time.LocalTime;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ScheduleSettingSegmentResponseDto {
    private Long id;
    private LocalTime startTime;
    private LocalTime endTime;
    private int requiredStaff;
}