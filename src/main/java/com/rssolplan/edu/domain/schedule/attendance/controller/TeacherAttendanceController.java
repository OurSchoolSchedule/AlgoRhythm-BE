package com.rssolplan.edu.domain.schedule.attendance.controller;

import com.rssolplan.edu.domain.schedule.attendance.TeacherAttendanceService;
import com.rssolplan.edu.domain.schedule.attendance.dto.TeacherAttendanceCheckInResponse;
import com.rssolplan.edu.domain.schedule.attendance.dto.TeacherAttendanceCheckOutResponse;
import com.rssolplan.edu.domain.schedule.attendance.dto.TeacherAttendanceTodayResponse;
import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/teacher-attendance")
@RequiredArgsConstructor
public class TeacherAttendanceController {

    private final TeacherAttendanceService teacherAttendanceService;

    @GetMapping("/today")
    public ResponseEntity<TeacherAttendanceTodayResponse> getToday(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(teacherAttendanceService.getTodayAttendance(userId));
    }

    @PostMapping("/check-in")
    public ResponseEntity<TeacherAttendanceCheckInResponse> checkIn(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(teacherAttendanceService.checkIn(userId));
    }

    @PostMapping("/check-out")
    public ResponseEntity<TeacherAttendanceCheckOutResponse> checkOut(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(teacherAttendanceService.checkOut(userId));
    }

    @OwnerOnly
    @GetMapping("/school/today")
    public ResponseEntity<List<TeacherAttendanceTodayResponse>> getSchoolAttendanceToday(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(teacherAttendanceService.getSchoolAttendanceToday(userId));
    }
}
