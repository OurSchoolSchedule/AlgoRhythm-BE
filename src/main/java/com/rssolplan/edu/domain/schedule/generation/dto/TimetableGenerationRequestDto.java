package com.rssolplan.edu.domain.schedule.generation.dto;

import com.rssolplan.edu.domain.schedule.generation.dto.candidate.GenerationOptionsDto;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class TimetableGenerationRequestDto {
    private int academicYear;
    private int semester;
    /** 채워야 할 슬롯 목록 (학급-요일-교시-과목) */
    private List<TimetableSlotRequirementDto> slotRequirements;
    private GenerationOptionsDto generationOptions;
}
