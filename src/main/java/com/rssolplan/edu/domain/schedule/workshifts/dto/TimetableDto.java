package com.rssolplan.edu.domain.schedule.workshifts.dto;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import lombok.Getter;

import java.time.LocalTime;

@Getter
public class TimetableDto {
    private final Long id;
    private final Long schoolId;
    private final int academicYear;
    private final int semester;
    private final Long schoolClassId;
    private final int grade;
    private final int classNumber;
    private final Long periodSettingId;
    private final int periodNumber;
    private final LocalTime periodStartTime;
    private final LocalTime periodEndTime;
    private final DayOfWeek dayOfWeek;
    private final Long subjectId;
    private final String subjectName;
    private final Long teacherId;
    private final String teacherName;

    public TimetableDto(Timetable t) {
        this.id = t.getId();
        this.schoolId = t.getSchool().getId();
        this.academicYear = t.getAcademicYear();
        this.semester = t.getSemester();
        this.schoolClassId = t.getSchoolClass().getId();
        this.grade = t.getSchoolClass().getGrade();
        this.classNumber = t.getSchoolClass().getClassNumber();
        this.periodSettingId = t.getPeriodSetting().getId();
        this.periodNumber = t.getPeriodSetting().getPeriodNumber();
        this.periodStartTime = t.getPeriodSetting().getStartTime();
        this.periodEndTime = t.getPeriodSetting().getEndTime();
        this.dayOfWeek = t.getDayOfWeek();
        this.subjectId = t.getSubject().getId();
        this.subjectName = t.getSubject().getName();
        this.teacherId = t.getTeacher().getId();
        this.teacherName = t.getTeacher().getUser().getUsername();
    }
}
