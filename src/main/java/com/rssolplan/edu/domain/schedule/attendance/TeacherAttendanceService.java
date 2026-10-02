package com.rssolplan.edu.domain.schedule.attendance;

import com.rssolplan.edu.domain.schedule.attendance.dto.TeacherAttendanceCheckInResponse;
import com.rssolplan.edu.domain.schedule.attendance.dto.TeacherAttendanceCheckOutResponse;
import com.rssolplan.edu.domain.schedule.attendance.dto.TeacherAttendanceTodayResponse;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeacherAttendanceService {

    private final TeacherAttendanceRepository attendanceRepository;
    private final AuthorizationService authService;

    private SchoolUser resolveSchoolUser(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        return authService.getSchoolUserOrThrow(userId, schoolId);
    }

    @Transactional(readOnly = true)
    public TeacherAttendanceTodayResponse getTodayAttendance(Long userId) {
        SchoolUser schoolUser = resolveSchoolUser(userId);
        LocalDate today = LocalDate.now();
        return attendanceRepository.findBySchoolUser_IdAndWorkDate(schoolUser.getId(), today)
                .map(this::mapToTodayResponse)
                .orElseGet(() -> TeacherAttendanceTodayResponse.noRecord(today));
    }

    @Transactional
    public TeacherAttendanceCheckInResponse checkIn(Long userId) {
        SchoolUser schoolUser = resolveSchoolUser(userId);
        LocalDate today = LocalDate.now();

        TeacherAttendance attendance = getOrCreate(schoolUser, today);

        if (attendance.isCheckedIn()) {
            throw new IllegalStateException("이미 출근 처리가 되었습니다.");
        }
        if (attendance.getStatus() == TeacherAttendanceStatus.FINISHED) {
            throw new IllegalStateException("이미 퇴근 처리까지 되었습니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        attendance.setCheckedIn(true);
        attendance.setCheckInTime(now);
        attendance.setStatus(TeacherAttendanceStatus.WORKING);
        attendanceRepository.save(attendance);

        return new TeacherAttendanceCheckInResponse("출근 처리 완료", today, TeacherAttendanceStatus.WORKING.name(), now);
    }

    @Transactional
    public TeacherAttendanceCheckOutResponse checkOut(Long userId) {
        SchoolUser schoolUser = resolveSchoolUser(userId);
        LocalDate today = LocalDate.now();

        TeacherAttendance attendance = attendanceRepository
                .findBySchoolUser_IdAndWorkDate(schoolUser.getId(), today)
                .orElseThrow(() -> new IllegalStateException("오늘 출근 기록이 없습니다."));

        if (!attendance.isCheckedIn()) {
            throw new IllegalStateException("출근 처리가 되어 있지 않습니다.");
        }
        if (attendance.isCheckedOut()) {
            throw new IllegalStateException("이미 퇴근 처리가 되었습니다.");
        }

        LocalDateTime now = LocalDateTime.now();
        attendance.setCheckedOut(true);
        attendance.setCheckOutTime(now);
        attendance.setStatus(TeacherAttendanceStatus.FINISHED);
        attendanceRepository.save(attendance);

        return new TeacherAttendanceCheckOutResponse("퇴근 처리 완료", today, TeacherAttendanceStatus.FINISHED.name(), now);
    }

    @Transactional(readOnly = true)
    public List<TeacherAttendanceTodayResponse> getSchoolAttendanceToday(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        LocalDate today = LocalDate.now();
        return attendanceRepository.findBySchoolUser_School_IdAndWorkDate(schoolId, today)
                .stream().map(this::mapToTodayResponse).collect(Collectors.toList());
    }

    private TeacherAttendance getOrCreate(SchoolUser schoolUser, LocalDate today) {
        return attendanceRepository.findBySchoolUser_IdAndWorkDate(schoolUser.getId(), today)
                .orElseGet(() -> attendanceRepository.save(
                        TeacherAttendance.builder()
                                .schoolUser(schoolUser)
                                .workDate(today)
                                .status(TeacherAttendanceStatus.BEFORE_WORK)
                                .build()));
    }

    private TeacherAttendanceTodayResponse mapToTodayResponse(TeacherAttendance a) {
        return new TeacherAttendanceTodayResponse(
                a.getWorkDate(),
                a.getStatus().name(),
                a.isCheckedIn(),
                a.isCheckedOut(),
                a.getCheckInTime(),
                a.getCheckOutTime()
        );
    }
}
