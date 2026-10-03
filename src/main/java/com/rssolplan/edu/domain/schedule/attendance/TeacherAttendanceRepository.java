package com.rssolplan.edu.domain.schedule.attendance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TeacherAttendanceRepository extends JpaRepository<TeacherAttendance, Long> {

    Optional<TeacherAttendance> findBySchoolUser_IdAndWorkDate(Long schoolUserId, LocalDate workDate);

    List<TeacherAttendance> findBySchoolUser_Id(Long schoolUserId);

    /**
     * Returns a membership's attendance records within the inclusive date range.
     * Dates with no stored record are omitted.
     */
    List<TeacherAttendance> findBySchoolUser_IdAndWorkDateBetween(Long schoolUserId, LocalDate from, LocalDate to);

    List<TeacherAttendance> findBySchoolUser_School_IdAndWorkDate(Long schoolId, LocalDate workDate);
}
