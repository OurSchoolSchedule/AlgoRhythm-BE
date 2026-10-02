package com.rssolplan.edu.domain.schedule.workavailability;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeacherAvailabilityRepository extends JpaRepository<TeacherAvailability, Long> {

    List<TeacherAvailability> findBySchoolUser_Id(Long schoolUserId);

    List<TeacherAvailability> findBySchoolUser_IdAndDayOfWeek(Long schoolUserId, DayOfWeek dayOfWeek);

    Optional<TeacherAvailability> findBySchoolUser_IdAndDayOfWeekAndPeriodNumber(
            Long schoolUserId, DayOfWeek dayOfWeek, int periodNumber);

    boolean existsBySchoolUser_IdAndDayOfWeekAndPeriodNumber(
            Long schoolUserId, DayOfWeek dayOfWeek, int periodNumber);

    void deleteBySchoolUser_Id(Long schoolUserId);
}
