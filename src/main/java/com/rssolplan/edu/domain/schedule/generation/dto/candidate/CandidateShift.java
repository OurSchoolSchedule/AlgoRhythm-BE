package com.rssolplan.edu.domain.schedule.generation.dto.candidate;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import lombok.*;

@Data
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CandidateShift {
    private Long schoolUserId;      // 배정된 교사 SchoolUser ID
    private String teacherName;
    private Long schoolClassId;     // 배정 학급 ID
    private DayOfWeek dayOfWeek;
    private int periodNumber;       // 교시 번호
    private Long subjectId;         // 과목 ID (null 가능)
    private String status;          // "UNASSIGNED" or null

    public CandidateShift(Long schoolUserId, String teacherName, Long schoolClassId,
                          DayOfWeek dayOfWeek, int periodNumber, Long subjectId) {
        this.schoolUserId = schoolUserId;
        this.teacherName = teacherName;
        this.schoolClassId = schoolClassId;
        this.dayOfWeek = dayOfWeek;
        this.periodNumber = periodNumber;
        this.subjectId = subjectId;
        this.status = null;
    }

    public CandidateShift(Long schoolClassId, DayOfWeek dayOfWeek, int periodNumber,
                          Long subjectId, String status) {
        this.schoolUserId = null;
        this.teacherName = null;
        this.schoolClassId = schoolClassId;
        this.dayOfWeek = dayOfWeek;
        this.periodNumber = periodNumber;
        this.subjectId = subjectId;
        this.status = status;
    }
}
