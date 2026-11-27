package com.rssolplan.edu.domain.schedule.substitute.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record SubstituteCreateRequest(
        @Positive Long timetableId,
        @NotNull LocalDate substituteDate,
        String note,
        @Positive Long termId,
        @Positive Long datedLessonId,
        @PositiveOrZero Long expectedLessonRev
) {
    public SubstituteCreateRequest(Long timetableId, LocalDate substituteDate, String note) {
        this(timetableId, substituteDate, note, null, null, null);
    }

    @AssertTrue(message = "기존 시간표 ID 또는 게시 학기·날짜 수업 ID·수업 버전을 지정하세요.")
    public boolean isTargetValid() {
        if (timetableId != null) return termId == null && datedLessonId == null && expectedLessonRev == null;
        return termId != null && datedLessonId != null && expectedLessonRev != null;
    }
}
