package com.rssolplan.edu.domain.schedule.attendance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record TeacherAttendanceCheckOutResponse(
        String message,
        LocalDate workDate,
        String status,
        LocalDateTime checkOutTime
) {
}
