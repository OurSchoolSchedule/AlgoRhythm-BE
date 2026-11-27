package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.school.setting.PeriodSetting;
import com.rssolplan.edu.global.exception.BadRequestException;

import java.util.*;
import java.util.stream.Collectors;

final class GradeCalendar {
    private static final Set<DayOfWeek> WEEKDAYS = EnumSet.range(DayOfWeek.MON, DayOfWeek.FRI);

    private GradeCalendar() {}

    static List<Slot> union(List<GradeLastPeriod> calendar, Map<String, Integer> classGrades,
                            List<PeriodSetting> periods) {
        Set<Integer> registered = periods.stream()
                .map(PeriodSetting::getPeriodNumber).collect(Collectors.toSet());
        Set<Integer> expected = new TreeSet<>(classGrades.values());
        Set<Integer> configured = new HashSet<>();
        Set<Slot> slots = new TreeSet<>();
        for (GradeLastPeriod row : calendar) {
            if (row == null || !expected.contains(row.grade()) || !configured.add(row.grade()))
                throw new BadRequestException("학기 학급 명부의 학년만 한 번씩 지정하세요. 등록 학년: " + expected);
            if (row.lastPeriods() == null || !row.lastPeriods().keySet().equals(WEEKDAYS))
                throw new BadRequestException(row.grade() + "학년의 월~금 마지막 교시를 모두 지정하세요.");
            for (var entry : row.lastPeriods().entrySet()) {
                Integer end = entry.getValue();
                if (end == null || (end != 0 && !registered.contains(end)))
                    throw new BadRequestException(row.grade() + "학년 " + entry.getKey()
                            + "의 마지막 교시는 등록된 교시 번호 또는 0이어야 합니다.");
                for (int number : registered)
                    if (number <= end) slots.add(new Slot(entry.getKey(), number));
            }
        }
        expected.removeAll(configured);
        if (!expected.isEmpty()) throw new BadRequestException("편성 조건이 없는 학년: " + expected);
        return List.copyOf(slots);
    }

    // Masks use indices into the frozen slot union, including non-consecutive bell numbers.
    static Map<String, BitSet> closedSlots(List<Slot> slots, Map<String, Integer> classGrades,
                                            List<GradeLastPeriod> calendar) {
        if (calendar == null) return Map.of();
        Map<Integer, BitSet> byGrade = new HashMap<>();
        for (var row : calendar) {
            BitSet closed = new BitSet(slots.size());
            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                if (slot.period() > row.lastPeriods().get(slot.day())) closed.set(i);
            }
            byGrade.put(row.grade(), closed);
        }
        Map<String, BitSet> byClass = new HashMap<>();
        classGrades.forEach((id, grade) -> byClass.put(id, byGrade.get(grade)));
        return byClass;
    }
}
