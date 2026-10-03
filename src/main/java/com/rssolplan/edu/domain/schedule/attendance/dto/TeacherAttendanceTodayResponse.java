package com.rssolplan.edu.domain.schedule.attendance.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record TeacherAttendanceTodayResponse(
        LocalDate workDate,
        String status,
        boolean isCheckedIn,
        boolean isCheckedOut,
        LocalDateTime checkInTime,
        LocalDateTime checkOutTime
) {
    /** Returns a NO_RECORD response for the supplied date with false check flags and null timestamps. */
    public static TeacherAttendanceTodayResponse noRecord(LocalDate workDate) {
        return new TeacherAttendanceTodayResponse(workDate, "NO_RECORD", false, false, null, null);
    }
}
