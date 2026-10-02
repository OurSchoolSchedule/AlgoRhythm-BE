package com.rssolplan.edu.domain.schedule.extrashift.dto;

import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SubstituteRequestDetail(
        Long id,
        Long schoolId,
        Long timetableId,
        String dayOfWeek,
        int periodNumber,
        LocalDate substituteDate,
        String status,
        String note,
        LocalDateTime createdAt
) {
    public static SubstituteRequestDetail from(SubstituteRequest req) {
        return new SubstituteRequestDetail(
                req.getId(),
                req.getSchool().getId(),
                req.getTimetable().getId(),
                req.getTimetable().getDayOfWeek().name(),
                req.getTimetable().getPeriodSetting().getPeriodNumber(),
                req.getSubstituteDate(),
                req.getStatus().name(),
                req.getNote(),
                req.getCreatedAt()
        );
    }
}
