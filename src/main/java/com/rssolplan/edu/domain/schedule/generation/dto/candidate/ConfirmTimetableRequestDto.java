package com.rssolplan.edu.domain.schedule.generation.dto.candidate;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ConfirmTimetableRequestDto {
    private Integer candidateIndex;
    private int academicYear;
    private int semester;
    private LocalDate startDate;
    private LocalDate endDate;
}
