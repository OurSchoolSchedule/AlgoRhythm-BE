package com.rssolplan.edu.domain.schedule.workshifts;

import com.rssolplan.edu.domain.auth.dto.ApiResponse;
import com.rssolplan.edu.domain.schedule.workshifts.dto.PublishedWeek;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/terms/{termId}/timetable-week")
public class PublishedWeekController {
    private final TimetableService timetable;

    @GetMapping
    public PublishedWeek week(@AuthenticationPrincipal Long user, @PathVariable long termId,
                              @RequestParam(required = false) LocalDate date,
                              @RequestParam(required = false) String view,
                              @RequestParam(required = false) Long classId,
                              @RequestParam(required = false) Long teacherId) {
        return timetable.publishedWeek(user, termId, date, view, classId, teacherId);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Object> badParameter() {
        return ApiResponse.error("BAD_REQUEST", "조회 날짜와 시간표 대상 ID를 확인하세요.");
    }
}
