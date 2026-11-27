package com.rssolplan.edu.domain.schedule.generation;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TimetableRepository extends JpaRepository<Timetable, Long> {

    interface PublishedSubject {
        Long getTeacherId();
        Long getSubjectId();
        String getSubjectName();
        int getLessonCount();
    }

    @Query(value = """
            SELECT w.teacher_id AS teacherId, s.id AS subjectId, s.name AS subjectName,
                   COUNT(*) AS lessonCount
            FROM weekly_lesson w
            JOIN subject s ON s.id = w.subject_id AND s.school_id = :schoolId
            WHERE w.version_id = (SELECT id FROM schedule_version WHERE term_id = :termId
                                  ORDER BY version_no DESC LIMIT 1)
            GROUP BY w.teacher_id, s.id, s.name
            ORDER BY w.teacher_id, s.name, s.id
            """, nativeQuery = true)
    List<PublishedSubject> publishedSubjects(@Param("schoolId") Long schoolId, @Param("termId") Long termId);

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
