package com.rssolplan.edu.domain.schedule.generation;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TimetableRepository extends JpaRepository<Timetable, Long> {

    List<Timetable> findBySchool_Id(Long schoolId);

    List<Timetable> findBySchool_IdAndAcademicYearAndSemester(Long schoolId, int academicYear, int semester);

    List<Timetable> findBySchoolClass_Id(Long schoolClassId);

    List<Timetable> findByTeacher_Id(Long teacherId);

    List<Timetable> findByTeacher_IdAndAcademicYearAndSemester(Long teacherId, int academicYear, int semester);

    List<Timetable> findBySchool_IdAndAcademicYearAndSemesterAndDayOfWeek(
            Long schoolId, int academicYear, int semester, DayOfWeek dayOfWeek);

    boolean existsBySchoolClass_IdAndAcademicYearAndSemesterAndDayOfWeekAndPeriodSetting_Id(
            Long schoolClassId, int academicYear, int semester, DayOfWeek dayOfWeek, Long periodSettingId);

    boolean existsByTeacher_IdAndAcademicYearAndSemesterAndDayOfWeekAndPeriodSetting_Id(
            Long teacherId, int academicYear, int semester, DayOfWeek dayOfWeek, Long periodSettingId);

    boolean existsByPeriodSetting_Id(Long periodSettingId);
}
