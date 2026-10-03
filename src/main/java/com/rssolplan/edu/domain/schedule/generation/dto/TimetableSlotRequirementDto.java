package com.rssolplan.edu.domain.schedule.generation.dto;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TimetableSlotRequirementDto {
    private Long schoolClassId;
    private DayOfWeek dayOfWeek;
    private int periodNumber;
    private Long subjectId;
}
