package com.rssolplan.edu.domain.schedule.workshifts.dto;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TimetableCreateDto {
    private int academicYear;
    private int semester;
    private Long schoolClassId;
    private Long periodSettingId;
    private DayOfWeek dayOfWeek;
    private Long subjectId;
    private Long teacherSchoolUserId;
}
