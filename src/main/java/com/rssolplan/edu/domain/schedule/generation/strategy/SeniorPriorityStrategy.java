package com.rssolplan.edu.domain.schedule.generation.strategy;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.generation.ScheduleGenerationService.TimetableSettingSnapshot;
import com.rssolplan.edu.domain.schedule.generation.dto.TimetableSlotRequirementDto;
import com.rssolplan.edu.domain.schedule.generation.dto.candidate.CandidateSchedule;
import com.rssolplan.edu.domain.schedule.generation.dto.candidate.CandidateShift;
import com.rssolplan.edu.domain.schedule.workavailability.TeacherAvailability;
import com.rssolplan.edu.domain.school.SchoolUser;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * SENIOR_PRIORITY 전략: 경력자 우선 배치
 * - 입사일이 이른(경력이 많은) 교사 우선 배정
 */
@Component
public class SeniorPriorityStrategy implements ScheduleGenerationStrategy {

    @Override
    public CandidateSchedule generate(
            Long schoolId,
            TimetableSettingSnapshot settings,
            List<TeacherAvailability> unavailabilities,
            List<SchoolUser> teachers,
            Map<Long, String> teacherUsernameMap,
            Map<Long, LocalDate> teacherHireDateMap) {

        CandidateSchedule candidate = new CandidateSchedule(schoolId);
        Map<Long, Integer> assignmentCount = new HashMap<>();
        LocalDate now = LocalDate.now();
        Map<Long, Set<String>> unavailabilityMap = buildUnavailabilityMap(unavailabilities);
        // 교사별 이미 배정된 교시 집합 (teacherId -> Set of "dayOfWeek_period")
        Map<Long, Set<String>> occupancyMap = new HashMap<>();

        for (TimetableSlotRequirementDto slot : settings.getSlotRequirements()) {
            String occupancyKey = slot.getDayOfWeek() + "_" + slot.getPeriodNumber();
            List<SchoolUser> available = filterAvailableTeachers(
                    teachers, unavailabilityMap, slot.getDayOfWeek(), slot.getPeriodNumber())
                    .stream()
                    .filter(t -> !occupancyMap.getOrDefault(t.getId(), Set.of()).contains(occupancyKey))
                    .collect(Collectors.toList());

            // 경력 순 정렬 (입사일 이른 순), 동일 경력이면 적게 배정된 순
            available.sort((t1, t2) -> {
                LocalDate h1 = teacherHireDateMap.getOrDefault(t1.getId(), now);
                LocalDate h2 = teacherHireDateMap.getOrDefault(t2.getId(), now);
                int cmp = h1.compareTo(h2);
                if (cmp != 0) return cmp;
                return Integer.compare(
                        assignmentCount.getOrDefault(t1.getId(), 0),
                        assignmentCount.getOrDefault(t2.getId(), 0));
            });

            if (!available.isEmpty()) {
                SchoolUser assigned = available.get(0);
                candidate.addShift(new CandidateShift(
                        assigned.getId(), teacherUsernameMap.get(assigned.getId()),
                        slot.getSchoolClassId(), slot.getDayOfWeek(),
                        slot.getPeriodNumber(), slot.getSubjectId()));
                assignmentCount.merge(assigned.getId(), 1, Integer::sum);
                occupancyMap.computeIfAbsent(assigned.getId(), k -> new HashSet<>()).add(occupancyKey);
            } else {
                candidate.addShift(new CandidateShift(
                        slot.getSchoolClassId(), slot.getDayOfWeek(),
                        slot.getPeriodNumber(), slot.getSubjectId(), "UNASSIGNED"));
            }
        }

        return candidate;
    }

    private Map<Long, Set<String>> buildUnavailabilityMap(List<TeacherAvailability> unavailabilities) {
        Map<Long, Set<String>> map = new HashMap<>();
        for (TeacherAvailability ua : unavailabilities) {
            map.computeIfAbsent(ua.getSchoolUser().getId(), k -> new HashSet<>())
               .add(ua.getDayOfWeek() + "_" + ua.getPeriodNumber());
        }
        return map;
    }

    private List<SchoolUser> filterAvailableTeachers(List<SchoolUser> teachers,
                                                      Map<Long, Set<String>> unavailabilityMap,
                                                      DayOfWeek dayOfWeek, int periodNumber) {
        String slotKey = dayOfWeek + "_" + periodNumber;
        return teachers.stream()
                .filter(t -> !unavailabilityMap.getOrDefault(t.getId(), Set.of()).contains(slotKey))
                .collect(Collectors.toList());
    }

    @Override
    public String getStrategyName() { return "SENIOR_PRIORITY"; }

    @Override
    public String getDescription() { return "경력자 우선 배치 (입사일 이른 순 배정)"; }
}
