package com.rssolplan.edu.domain.schedule.operations;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.candidates.CandidateApi.GradeLastPeriod;
import com.rssolplan.edu.domain.schedule.candidates.CandidateApi.Slot;

import java.util.Map;
import java.util.TreeMap;

/** Operating periods from the generation snapshot attached to the effective published version. */
public record PublishedHours(Map<Integer, Integer> lastPeriodByGrade, Integer lastPeriod) {
    public static PublishedHours forDay(ScheduleJson json, DayOfWeek day, String gradeRows, String slots) {
        var ends = new TreeMap<Integer, Integer>();
        if (gradeRows != null && !gradeRows.equals("null")) {
            for (var row : json.read(gradeRows, GradeLastPeriod[].class))
                ends.put(row.grade(), row.lastPeriods().getOrDefault(day, 0));
        }
        Integer common = null;
        if (slots != null && !slots.equals("null")) {
            common = 0;
            for (var slot : json.read(slots, Slot[].class))
                if (slot.day() == day) common = Math.max(common, slot.period());
        }
        return new PublishedHours(ends, common);
    }
}
