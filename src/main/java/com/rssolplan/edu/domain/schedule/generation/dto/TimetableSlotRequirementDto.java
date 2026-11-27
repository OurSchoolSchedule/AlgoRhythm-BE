package com.rssolplan.edu.domain.schedule.generation.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.ScheduleInteger;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TimetableSlotRequirementDto {
    private Long schoolClassId;
    private DayOfWeek dayOfWeek;
    @JsonDeserialize(using = ScheduleInteger.class)
    private int periodNumber;
    private Long subjectId;
}
