package com.rssolplan.edu.domain.schedule.generation.strategy;

import com.rssolplan.edu.domain.schedule.generation.ScheduleGenerationService.TimetableSettingSnapshot;
import com.rssolplan.edu.domain.schedule.generation.dto.candidate.CandidateSchedule;
import com.rssolplan.edu.domain.schedule.workavailability.TeacherAvailability;
import com.rssolplan.edu.domain.school.SchoolUser;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시간표 생성 전략 인터페이스 (교시 기반)
 */
public interface ScheduleGenerationStrategy {

    /**
     * 후보 시간표 생성
     *
     * @param schoolId               학교 ID
     * @param settings               시간표 설정 스냅샷 (교시 정보)
     * @param unavailabilities       교사 불가 교시 목록
     * @param teachers               학교 소속 교사 목록
     * @param teacherUsernameMap     schoolUserId -> username 매핑
     * @param teacherHireDateMap     schoolUserId -> hireDate 매핑
     * @return 생성된 후보 시간표
     */
    CandidateSchedule generate(
            Long schoolId,
            TimetableSettingSnapshot settings,
            List<TeacherAvailability> unavailabilities,
            List<SchoolUser> teachers,
            Map<Long, String> teacherUsernameMap,
            Map<Long, LocalDate> teacherHireDateMap
    );

    String getStrategyName();

    String getDescription();
}
