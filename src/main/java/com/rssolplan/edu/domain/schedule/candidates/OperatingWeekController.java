package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.operations.ScheduleAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@RestController
@RequestMapping("/api/v2/terms/{term}/operating-week")
@RequiredArgsConstructor
class OperatingWeekController {
    private final ScheduleAccess access;
    private final JdbcTemplate db;
    private final PublishedOperatingHours published;

    record Day(LocalDate date, String status, Long versionId, List<Integer> periods,
               Map<Integer, List<Integer>> gradePeriods, Map<String, Integer> classGrades) {}
    record Week(LocalDate weekStart, List<Day> days) {}
    private record ScheduleDay(LocalDate date, String status, Long versionId) {}

    @GetMapping
    @Transactional(readOnly = true)
    Week read(@AuthenticationPrincipal Long user, @PathVariable long term, @RequestParam LocalDate weekOf) {
        access.schoolOfTerm(user, term, false);
        var monday = weekOf.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        // Match the dated timetable reader: greatest published revision effective on each day.
        var dates = db.query("""
                with week_dates as (
                    select date_add(?, interval offset_day day) as lesson_date from
                      (select 0 offset_day union all select 1 union all select 2 union all select 3 union all select 4) n
                )
                select d.lesson_date,
                       case when d.lesson_date not between t.start_date and t.end_date then 'OUTSIDE_TERM'
                            when x.term_id is not null then 'EXCLUDED' else 'OPEN' end as day_status,
                       (select v.id from schedule_version v where v.term_id=t.id and v.effective_from<=d.lesson_date
                        order by v.version_no desc limit 1) as version_id
                from timetable_set t cross join week_dates d
                left join term_excluded_date x on x.term_id=t.id and x.excluded_date=d.lesson_date
                where t.id=? order by d.lesson_date
                """, (r, i) -> new ScheduleDay(r.getObject(1, LocalDate.class), r.getString(2),
                        r.getObject(3, Long.class)), monday, term);
        var snapshots = new HashMap<Long, Optional<PublishedOperatingHours.Calendar>>();
        var days = new ArrayList<Day>();
        for (var date : dates) {
            if (!date.status().equals("OPEN")) {
                days.add(new Day(date.date(), date.status(), null, List.of(), Map.of(), Map.of()));
                continue;
            }
            var id = date.versionId();
            var calendar = id == null ? Optional.<PublishedOperatingHours.Calendar>empty()
                    : snapshots.computeIfAbsent(id, published::forVersion);
            days.add(calendar.map(value -> day(date.date(), id, value))
                    .orElseGet(() -> new Day(date.date(), date.status(), id, null, Map.of(), Map.of())));
        }
        return new Week(monday, days);
    }

    static Day day(LocalDate date, long id, PublishedOperatingHours.Calendar calendar) {
        var weekday = DayOfWeek.values()[date.getDayOfWeek().getValue() - 1];
        var periods = calendar.teachingSlots().stream().filter(s -> s.day() == weekday)
                .map(CandidateApi.Slot::period).distinct().sorted().toList();
        var byGrade = new TreeMap<Integer, List<Integer>>();
        if (calendar.gradeLastPeriods() != null) {
            for (var grade : calendar.gradeLastPeriods()) {
                int last = grade.lastPeriods().get(weekday);
                byGrade.put(grade.grade(), periods.stream().filter(p -> p <= last).toList());
            }
        }
        return new Day(date, "OPEN", id, periods, byGrade, calendar.classGrades() == null ? Map.of() : calendar.classGrades());
    }
}
