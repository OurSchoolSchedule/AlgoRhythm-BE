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

    /**
     * Returns the user's membership in the active school.
     *
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active or membership is missing
     */
    private SchoolUser resolveSchoolUser(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        return authService.getSchoolUserOrThrow(userId, schoolId);
    }

    /**
     * Returns attendance for the active-school membership on the server's current local date.
     * Returns NO_RECORD with false check flags and null times when no record exists.
     *
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active or membership is missing
     */
    @Transactional(readOnly = true)
    public TeacherAttendanceTodayResponse getTodayAttendance(Long userId) {
        SchoolUser schoolUser = resolveSchoolUser(userId);
        LocalDate today = LocalDate.now();
        return attendanceRepository.findBySchoolUser_IdAndWorkDate(schoolUser.getId(), today)
                .map(this::mapToTodayResponse)
                .orElseGet(() -> TeacherAttendanceTodayResponse.noRecord(today));
    }

    /**
     * Records check-in at the server's current local time for today's active-school membership,
     * creating a daily record if necessary. Returns WORKING status and the check-in timestamp.
     *
     * @throws IllegalStateException if already checked in or the record has FINISHED status
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active or membership is missing
     */
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

    /**
     * Records check-out at the server's current local time for today's active-school membership.
     * Returns FINISHED status and the check-out timestamp.
     *
     * @throws IllegalStateException if there is no checked-in record for today or it is already checked out
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active or membership is missing
     */
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

    /**
     * Returns existing attendance records for the active school on the server's current
     * local date. Members without a record are omitted.
     *
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
    @Transactional(readOnly = true)
    public List<TeacherAttendanceTodayResponse> getSchoolAttendanceToday(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        LocalDate today = LocalDate.now();
        return attendanceRepository.findBySchoolUser_School_IdAndWorkDate(schoolId, today)
                .stream().map(this::mapToTodayResponse).collect(Collectors.toList());
    }

    /**
     * Returns the member's record for the supplied date, persisting a BEFORE_WORK record
     * when none exists.
     */
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
