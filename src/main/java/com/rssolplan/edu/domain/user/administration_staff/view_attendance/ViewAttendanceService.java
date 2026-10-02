package com.rssolplan.edu.domain.user.administration_staff.view_attendance;

import com.rssolplan.edu.domain.schedule.attendance.TeacherAttendance;
import com.rssolplan.edu.domain.schedule.attendance.TeacherAttendanceRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ViewAttendanceService {

    private final TeacherAttendanceRepository teacherAttendanceRepository;
    private final SchoolUserRepository schoolUserRepository;

    @Transactional(readOnly = true)
    public ViewAttendanceResponse getEmployeeAttendance(
            Long adminId, Long schoolUserId, LocalDate startDate, LocalDate endDate) {

        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("INVALID_DATE_RANGE");
        }

        SchoolUser schoolUser = schoolUserRepository.findById(schoolUserId)
                .orElseThrow(() -> new IllegalArgumentException("SCHOOL_USER_NOT_FOUND"));

        boolean isAdmin = schoolUserRepository
                .findByUser_IdAndSchool_Id(adminId, schoolUser.getSchool().getId())
                .stream()
                .anyMatch(su -> su.getPosition() == SchoolUser.Position.ADMIN);

        if (!isAdmin) {
            throw new IllegalArgumentException("ACCESS_DENIED");
        }

        List<TeacherAttendance> attendanceList =
                teacherAttendanceRepository.findBySchoolUser_IdAndWorkDateBetween(
                        schoolUserId, startDate, endDate);

        Map<LocalDate, TeacherAttendance> attendanceMap = new HashMap<>();
        for (TeacherAttendance a : attendanceList) {
            attendanceMap.put(a.getWorkDate(), a);
        }

        List<ViewAttendanceDayDto> result = new ArrayList<>();
        int normalCount = 0, lateCount = 0, absentCount = 0;

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            TeacherAttendance attendance = attendanceMap.get(date);
            String status = resolveAttendance(attendance);

            if ("NORMAL".equals(status)) normalCount++;
            else if ("LATE".equals(status)) lateCount++;
            else if ("ABSENT".equals(status)) absentCount++;

            result.add(new ViewAttendanceDayDto(date, status));
        }

        return new ViewAttendanceResponse(
                schoolUserId,
                schoolUser.getUser().getUsername(),
                schoolUser.getPosition().name(),
                normalCount,
                lateCount,
                absentCount,
                startDate,
                endDate,
                result
        );
    }

    private String resolveAttendance(TeacherAttendance attendance) {
        if (attendance == null || !attendance.isCheckedIn()) {
            return "ABSENT";
        }
        return switch (attendance.getStatus()) {
            case WORKING, FINISHED -> "NORMAL";
            case ABSENT -> "ABSENT";
            case LEAVE -> "LEAVE";
            default -> "OFF";
        };
    }
}
