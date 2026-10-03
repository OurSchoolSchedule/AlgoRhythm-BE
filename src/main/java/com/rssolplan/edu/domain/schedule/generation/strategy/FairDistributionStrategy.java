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
 * FAIR_DISTRIBUTION 전략: 공정 배분
 * - 모든 교사의 수업 수 편차 최소화
 * - 가장 적게 배정된 교사 우선 배정
 */
@Component
public class FairDistributionStrategy implements ScheduleGenerationStrategy {

    /**
     * Assigns slots in input order to the eligible teacher with the fewest assignments,
     * breaking ties in favor of the later hire date.
     * Excludes declared unavailable periods and teachers already selected for the same
     * day and period. Slots with no eligible teacher have UNASSIGNED status. Metadata
     * counts are calculated by the caller.
     *
     * @param teacherUsernameMap names keyed by SchoolUser ID
     * @param teacherHireDateMap hire dates keyed by SchoolUser ID; absent entries use today
     */
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
        teachers.forEach(t -> assignmentCount.put(t.getId(), 0));
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

            // 수업 수 적은 순, 동일하면 신입(입사일 늦은) 순으로 기회 부여
            available.sort((t1, t2) -> {
                int c1 = assignmentCount.getOrDefault(t1.getId(), 0);
                int c2 = assignmentCount.getOrDefault(t2.getId(), 0);
                if (c1 != c2) return Integer.compare(c1, c2);
                LocalDate h1 = teacherHireDateMap.getOrDefault(t1.getId(), now);
                LocalDate h2 = teacherHireDateMap.getOrDefault(t2.getId(), now);
                return h2.compareTo(h1); // 신입 우선
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

    /** Groups unavailable day/period keys by SchoolUser ID, collapsing duplicate entries. */
    private Map<Long, Set<String>> buildUnavailabilityMap(List<TeacherAvailability> unavailabilities) {
        Map<Long, Set<String>> map = new HashMap<>();
        for (TeacherAvailability ua : unavailabilities) {
            map.computeIfAbsent(ua.getSchoolUser().getId(), k -> new HashSet<>())
               .add(ua.getDayOfWeek() + "_" + ua.getPeriodNumber());
        }
        return map;
    }

    /**
     * Returns teachers in input order whose declared unavailability does not include the
     * specified day and period. Assignment conflicts are filtered separately by the caller.
     */
    private List<SchoolUser> filterAvailableTeachers(List<SchoolUser> teachers,
                                                      Map<Long, Set<String>> unavailabilityMap,
                                                      DayOfWeek dayOfWeek, int periodNumber) {
        String slotKey = dayOfWeek + "_" + periodNumber;
        return teachers.stream()
                .filter(t -> !unavailabilityMap.getOrDefault(t.getId(), Set.of()).contains(slotKey))
                .collect(Collectors.toList());
    }

    @Override
    public String getStrategyName() { return "FAIR_DISTRIBUTION"; }

    @Override
    public String getDescription() { return "수업 수 공정 배분 (모든 교사의 수업 수 편차 최소화)"; }
}
