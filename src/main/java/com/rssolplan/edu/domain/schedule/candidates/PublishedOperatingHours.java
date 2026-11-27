package com.rssolplan.edu.domain.schedule.candidates;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.candidates.CandidateApi.GradeLastPeriod;
import com.rssolplan.edu.domain.schedule.candidates.CandidateApi.Slot;
import com.rssolplan.edu.domain.schedule.operations.ScheduleJson;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/** Immutable teaching windows and bell IDs used to generate a published version. */
@Service
@RequiredArgsConstructor
public class PublishedOperatingHours {
    private final JdbcTemplate db;
    private final ScheduleJson json;

    public record Calendar(List<Slot> teachingSlots, List<GradeLastPeriod> gradeLastPeriods,
                           Map<String, Integer> classGrades, Map<Integer, Long> periodIds) {
        public List<Integer> periodsOn(LocalDate date) {
            var day = DayOfWeek.values()[date.getDayOfWeek().getValue() - 1];
            return teachingSlots.stream().filter(slot -> slot.day() == day)
                    .map(Slot::period).distinct().sorted().toList();
        }

        public Map<Integer,List<Integer>> gradePeriodsOn(LocalDate date, List<Integer> periods) {
            if (gradeLastPeriods == null) return Map.of();
            var day = DayOfWeek.values()[date.getDayOfWeek().getValue() - 1];
            var gradePeriods = new TreeMap<Integer,List<Integer>>();
            for (var grade : gradeLastPeriods) {
                int end = grade.lastPeriods().getOrDefault(day, 0);
                gradePeriods.put(grade.grade(), periods.stream().filter(p -> p <= end).toList());
            }
            return gradePeriods;
        }

        public boolean allows(long classId, LocalDate date, long periodId) {
            var day = DayOfWeek.values()[date.getDayOfWeek().getValue() - 1];
            var slot = teachingSlots.stream().filter(s -> s.day() == day
                    && Objects.equals(periodIds.get(s.period()), periodId)).findFirst();
            if (slot.isEmpty()) return false;
            if (gradeLastPeriods == null) return true;
            var grade = classGrades == null ? null : classGrades.get(Long.toString(classId));
            return grade != null && gradeLastPeriods.stream().anyMatch(row -> row.grade() == grade
                    && slot.get().period() <= row.lastPeriods().getOrDefault(day, 0));
        }
    }

    /** Versions created before generation receipts have no saved timetable constraints. */
    public Optional<Calendar> forVersion(long versionId) {
        return db.query("""
                select json_extract(j.snapshot,'$.slots'), json_extract(j.snapshot,'$.request.gradeLastPeriods'),
                       json_extract(j.snapshot,'$.classGrades'), json_extract(j.snapshot,'$.bells')
                from publication_receipt r join generation_job j on j.id=r.job_id where r.version_id=?
                """, (row, n) -> {
                    var slots = List.of(json.read(row.getString(1), Slot[].class));
                    String limits = row.getString(2), grades = row.getString(3);
                    var bells = json.read(row.getString(4), new TypeReference<Map<Integer, CandidateInputService.Bell>>() {});
                    Map<Integer, Long> ids = new TreeMap<>();
                    bells.forEach((number, bell) -> ids.put(number, bell.id()));
                    return new Calendar(slots,
                            limits == null || limits.equals("null") ? null : List.of(json.read(limits, GradeLastPeriod[].class)),
                            grades == null ? null : json.read(grades, new TypeReference<Map<String, Integer>>() {}),
                            ids);
                }, versionId).stream().findFirst();
    }
}
