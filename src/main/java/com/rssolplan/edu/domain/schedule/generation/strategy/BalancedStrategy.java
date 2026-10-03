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
 * BALANCED 전략: 경력자-신입 균형 배치
 * - 2인 이상 배정 시 경력자(1년 이상) 최소 1명 배치
 */
@Component
public class BalancedStrategy implements ScheduleGenerationStrategy {

    private static final int SENIOR_THRESHOLD_MONTHS = 12;

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
        // 교사별 이미 배정된 교시 집합 (teacherId -> Set of "dayOfWeek_period")
        Map<Long, Set<String>> occupancyMap = new HashMap<>();
        LocalDate now = LocalDate.now();

        // 교사별 불가 교시 집합 (schoolUserId -> Set of "dayOfWeek_period")
        Map<Long, Set<String>> unavailabilityMap = buildUnavailabilityMap(unavailabilities);

        for (TimetableSlotRequirementDto slot : settings.getSlotRequirements()) {
            String occupancyKey = slot.getDayOfWeek() + "_" + slot.getPeriodNumber();
            // 해당 슬롯에 배정 가능한 교사 필터링 (불가 교시 + 이미 같은 시간 배정된 교사 제외)
            List<SchoolUser> available = filterAvailableTeachers(
                    teachers, unavailabilityMap, slot.getDayOfWeek(), slot.getPeriodNumber())
                    .stream()
                    .filter(t -> !occupancyMap.getOrDefault(t.getId(), Set.of()).contains(occupancyKey))
                    .collect(Collectors.toList());

            // 경력자/신입 분류
            List<SchoolUser> seniors = new ArrayList<>();
            List<SchoolUser> juniors = new ArrayList<>();
            for (SchoolUser t : available) {
                LocalDate hireDate = teacherHireDateMap.getOrDefault(t.getId(), now);
                long months = java.time.temporal.ChronoUnit.MONTHS.between(hireDate, now);
                if (months >= SENIOR_THRESHOLD_MONTHS) seniors.add(t);
                else juniors.add(t);
            }

            Comparator<SchoolUser> byCount = Comparator.comparingInt(t ->
                    assignmentCount.getOrDefault(t.getId(), 0));
            seniors.sort(byCount);
            juniors.sort(byCount);

            // 경력자 우선 배치 (1명), 나머지는 번갈아
            SchoolUser assigned = null;
            if (!seniors.isEmpty()) {
                assigned = seniors.remove(0);
            } else if (!juniors.isEmpty()) {
                assigned = juniors.remove(0);
            }

            if (assigned != null) {
                candidate.addShift(new CandidateShift(
                        assigned.getId(),
                        teacherUsernameMap.get(assigned.getId()),
                        slot.getSchoolClassId(),
                        slot.getDayOfWeek(),
                        slot.getPeriodNumber(),
                        slot.getSubjectId()
                ));
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
            String key = ua.getDayOfWeek() + "_" + ua.getPeriodNumber();
            map.computeIfAbsent(ua.getSchoolUser().getId(), k -> new HashSet<>()).add(key);
        }
        return map;
    }

    private List<SchoolUser> filterAvailableTeachers(List<SchoolUser> teachers,
                                                      Map<Long, Set<String>> unavailabilityMap,
                                                      DayOfWeek dayOfWeek, int periodNumber) {
        String slotKey = dayOfWeek + "_" + periodNumber;
        return teachers.stream()
                .filter(t -> {
                    Set<String> unavail = unavailabilityMap.getOrDefault(t.getId(), Set.of());
                    return !unavail.contains(slotKey);
                })
                .collect(Collectors.toList());
    }

    @Override
    public String getStrategyName() { return "BALANCED"; }

    @Override
    public String getDescription() { return "경력자-신입 균형 배치 (경력자 우선, 나머지 신입 교사 배치)"; }
}
