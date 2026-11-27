package com.rssolplan.edu.domain.schedule.substitute.dto;

import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteRequest;

import java.util.List;
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
        LocalDateTime createdAt,
        Long termId,
        Long datedLessonId,
        Long expectedLessonRev,
        List<SubstituteResponseDetail> responses,
        boolean canWithdraw
) {
    public static SubstituteRequestDetail from(SubstituteRequest req) {
        return from(req, List.of(), false);
    }

    public static SubstituteRequestDetail from(SubstituteRequest req, List<SubstituteResponseDetail> responses, boolean canWithdraw) {
        return new SubstituteRequestDetail(
                req.getId(),
                req.getSchool().getId(),
                req.getTimetable() == null ? null : req.getTimetable().getId(),
                req.weekday(),
                req.periodNumber(),
                req.getSubstituteDate(),
                req.getStatus().name(),
                req.getNote(),
                req.getCreatedAt(),
                req.getDatedTermId(),
                req.getDatedLessonId(),
                req.getExpectedLessonRev(), responses, canWithdraw
        );
    }
}
