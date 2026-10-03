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
 * COVERAGE_FIRST 전략: 빈 교시 최소화 우선
 * - 배정 가능한 교사가 적은 슬롯부터 먼저 배정
 */
@Component
public class CoverageFirstStrategy implements ScheduleGenerationStrategy {

    /**
     * Assigns slots with the fewest teachers available by declared unavailability first,
     * choosing the eligible teacher with the fewest assignments. Returns shifts in input
     * order. Repeated class/day/period keys share the last assignment for that key.
     * Excludes declared unavailable periods and teachers already selected for the same
     * day and period. Slots with no eligible teacher have UNASSIGNED status. Metadata
     * counts are calculated by the caller.
     *
     * @param teacherUsernameMap names keyed by SchoolUser ID
     * @param teacherHireDateMap unused by this strategy
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
        Map<Long, Set<String>> unavailabilityMap = buildUnavailabilityMap(unavailabilities);
        // 교사별 이미 배정된 교시 집합 (teacherId -> Set of "dayOfWeek_period")
        Map<Long, Set<String>> occupancyMap = new HashMap<>();

        // 슬롯을 배정 가능 교사 수 기준으로 정렬 (적은 순 → 먼저 배정)
        List<TimetableSlotRequirementDto> sorted = settings.getSlotRequirements().stream()
                .sorted(Comparator.comparingInt(slot ->
                        filterAvailableTeachers(teachers, unavailabilityMap,
                                slot.getDayOfWeek(), slot.getPeriodNumber()).size()))
                .collect(Collectors.toList());

        Map<String, SchoolUser> slotAssignment = new HashMap<>();

        for (TimetableSlotRequirementDto slot : sorted) {
            String slotKey = slot.getSchoolClassId() + "_" + slot.getDayOfWeek() + "_" + slot.getPeriodNumber();
            String occupancyKey = slot.getDayOfWeek() + "_" + slot.getPeriodNumber();
            List<SchoolUser> available = filterAvailableTeachers(
                    teachers, unavailabilityMap, slot.getDayOfWeek(), slot.getPeriodNumber())
                    .stream()
                    .filter(t -> !occupancyMap.getOrDefault(t.getId(), Set.of()).contains(occupancyKey))
                    .collect(Collectors.toList());

            // 적게 배정된 순으로 정렬
            available.sort(Comparator.comparingInt(t -> assignmentCount.getOrDefault(t.getId(), 0)));

            SchoolUser assigned = available.isEmpty() ? null : available.get(0);
            slotAssignment.put(slotKey, assigned);
            if (assigned != null) {
                assignmentCount.merge(assigned.getId(), 1, Integer::sum);
                occupancyMap.computeIfAbsent(assigned.getId(), k -> new HashSet<>()).add(occupancyKey);
            }
        }

        // 원래 순서로 CandidateSchedule에 추가
        for (TimetableSlotRequirementDto slot : settings.getSlotRequirements()) {
            String slotKey = slot.getSchoolClassId() + "_" + slot.getDayOfWeek() + "_" + slot.getPeriodNumber();
            SchoolUser assigned = slotAssignment.get(slotKey);
            if (assigned != null) {
                candidate.addShift(new CandidateShift(
                        assigned.getId(), teacherUsernameMap.get(assigned.getId()),
                        slot.getSchoolClassId(), slot.getDayOfWeek(),
                        slot.getPeriodNumber(), slot.getSubjectId()));
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
            String key = ua.getDayOfWeek() + "_" + ua.getPeriodNumber();
            map.computeIfAbsent(ua.getSchoolUser().getId(), k -> new HashSet<>()).add(key);
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
    public String getStrategyName() { return "COVERAGE_FIRST"; }

    @Override
    public String getDescription() { return "빈 교시 최소화 우선 (배정 가능 교사 적은 슬롯부터 먼저 배정)"; }
}
