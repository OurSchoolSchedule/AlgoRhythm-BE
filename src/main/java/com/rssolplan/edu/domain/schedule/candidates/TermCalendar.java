package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.schedule.candidates.PublicationService.*;
import com.rssolplan.edu.domain.schedule.operations.ScheduleConflict;
import com.rssolplan.edu.global.exception.BadRequestException;
import com.rssolplan.edu.global.exception.NotFoundException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class TermCalendar {
    private final JdbcTemplate db;

    List<TermSummary> terms(long school) {
        var exclusions = new HashMap<Long, List<LocalDate>>();
        db.query("""
                select x.term_id,x.excluded_date from term_excluded_date x
                join timetable_set t on t.id=x.term_id where t.school_id=? order by x.excluded_date
                """, row -> {
            exclusions.computeIfAbsent(row.getLong(1), id -> new ArrayList<>()).add(row.getObject(2, LocalDate.class));
        }, school);
        return db.query("""
                select id,academic_year,semester,start_date,end_date from timetable_set
                where school_id=? order by start_date desc,id desc
                """, (r, n) -> new TermSummary(r.getLong(1), r.getInt(2), r.getInt(3),
                r.getObject(4, LocalDate.class), r.getObject(5, LocalDate.class),
                exclusions.getOrDefault(r.getLong(1), List.of())), school);
    }

    Set<LocalDate> exclusions(long term) {
        return new HashSet<>(db.queryForList("select excluded_date from term_excluded_date where term_id=?",
                LocalDate.class, term));
    }

    long create(long school, NewTerm term) {
        List<LocalDate> excluded = term.excludedDates() == null ? List.of() : term.excludedDates();
        validate(term.startDate(), term.endDate(), excluded);
        checkOverlap(school, 0, term.academicYear(), term.semester(), term.startDate(), term.endDate());
        db.update("insert into timetable_set(school_id,academic_year,semester,start_date,end_date) values(?,?,?,?,?)",
                school, term.academicYear(), term.semester(), term.startDate(), term.endDate());
        long id = db.queryForObject("select last_insert_id()", Long.class);
        addExclusions(id, excluded);
        return id;
    }

    TermSummary edit(long school, long term, CalendarEdit edit) {
        validate(edit.startDate(), edit.endDate(), edit.excludedDates());
        var previous = terms(school).stream().filter(row -> row.termId() == term).findFirst()
                .orElseThrow(() -> new NotFoundException("학기를 찾을 수 없습니다."));
        var next = new TermSummary(term, previous.academicYear(), previous.semester(), edit.startDate(), edit.endDate(),
                edit.excludedDates().stream().sorted().toList());
        if (previous.equals(next)) return previous;
        var expected = edit.expectedCalendar();
        if (expected != null && (!previous.startDate().equals(expected.startDate())
                || !previous.endDate().equals(expected.endDate())
                || !new HashSet<>(previous.excludedDates()).equals(new HashSet<>(expected.excludedDates()))))
            throw new ScheduleConflict("TERM_CALENDAR_CHANGED", null, "다른 관리자가 학기 일정을 수정했습니다. 최신 값을 확인하세요.");
        checkOverlap(school, term, next.academicYear(), next.semester(), next.startDate(), next.endDate());
        var versions = new TreeMap<LocalDate, Long>();
        db.query("select effective_from,id from schedule_version where term_id=? order by version_no", r -> {
            var from = r.getObject(1, LocalDate.class);
            versions.tailMap(from, true).clear(); // A newer revision may be backdated.
            versions.put(from, r.getLong(2));
        }, term);
        if (db.queryForObject("""
                select exists(select 1 from schedule_version where term_id=? and effective_from not between ? and ?)
                """, Boolean.class, term, next.startDate(), next.endDate()))
            throw new ScheduleConflict("TERM_RANGE_CONFLICT", null, "게시된 시간표 적용일을 학기 범위에 포함하세요.");

        var oldDays = teachingDates(previous);
        var newDays = teachingDates(next);
        var removed = new HashSet<>(oldDays);
        removed.removeAll(newDays);
        newDays.removeAll(oldDays); // Remaining dates have become regular teaching dates.
        var changed = new HashSet<>(removed);
        changed.addAll(newDays);
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        if ((!versions.isEmpty() && changed.stream().anyMatch(d -> !d.isBefore(versions.firstKey()) && d.isBefore(today)))
                || !Collections.disjoint(changed, protectedDates(school, previous)))
            throw new ScheduleConflict("DATED_CHANGES_EXIST", null, "지난 게시 날짜 또는 수업 변경 요청이 있는 날짜는 수정할 수 없습니다.");

        db.update("update timetable_set set start_date=?,end_date=? where id=?", next.startDate(), next.endDate(), term);
        db.update("delete from term_excluded_date where term_id=?", term);
        addExclusions(term, next.excludedDates());
        for (var day : newDays) {
            var effective = versions.floorEntry(day);
            if (effective != null) materialize(effective.getValue(), day);
        }
        return next;
    }

    void materialize(long version, LocalDate date) {
        db.update("""
                insert into dated_lesson(origin_id,origin_date,teaching_date,planned,actual)
                select w.id,?,?,json_object('teacherId',w.teacher_id,'periodId',w.period_id),
                       json_object('teacherId',w.teacher_id,'periodId',w.period_id)
                from weekly_lesson w left join dated_lesson d on d.origin_id=w.id and d.origin_date=?
                where w.version_id=? and w.weekday=? and d.id is null
                """, date, date, date, version, date.getDayOfWeek().getValue());
    }

    private void checkOverlap(long school, long id, int year, int semester, LocalDate start, LocalDate end) {
        if (db.queryForObject("""
                select exists(select 1 from timetable_set where school_id=? and id<>?
                    and ((academic_year=? and semester=?) or (start_date<=? and end_date>=?)))
                """, Boolean.class, school, id, year, semester, end, start))
            throw new ScheduleConflict("TERM_EXISTS", null, "이미 등록된 학기 또는 기간입니다.");
    }

    private static void validate(LocalDate start, LocalDate end, List<LocalDate> excluded) {
        if (start == null || end == null || start.getYear() < 1000 || end.getYear() > 9999
                || end.isBefore(start) || end.isAfter(start.plusYears(1)))
            throw new BadRequestException("학기 시작일·마감일을 확인하세요. 학기 기간은 1년 이내여야 합니다.");
        if (excluded == null || excluded.stream().anyMatch(d -> d == null || d.isBefore(start) || d.isAfter(end)))
            throw new BadRequestException("정규수업 제외일은 학기 기간 안의 날짜여야 합니다.");
        if (new HashSet<>(excluded).size() != excluded.size())
            throw new BadRequestException("중복된 정규수업 제외일이 있습니다.");
    }

    private void addExclusions(long term, List<LocalDate> dates) {
        db.batchUpdate("insert into term_excluded_date(term_id,excluded_date) values(?,?)",
                dates.stream().map(date -> new Object[]{term, date}).toList());
    }

    private static Set<LocalDate> teachingDates(TermSummary term) {
        var result = new HashSet<LocalDate>();
        term.startDate().datesUntil(term.endDate().plusDays(1)).forEach(result::add);
        result.removeAll(term.excludedDates());
        return result;
    }

    private Set<LocalDate> protectedDates(long school, TermSummary term) {
        // Include outstanding invitations as well as approved moves and their original dates.
        return new HashSet<>(db.queryForList("""
                with dated as (
                    select d.origin_date,d.teaching_date from dated_lesson d
                    join weekly_lesson w on w.id=d.origin_id join schedule_version v on v.id=w.version_id
                    where v.term_id=? and (d.rev>0 or exists(select 1 from lesson_change c
                        where c.term_id=v.term_id and (c.first_id=d.id or c.second_id=d.id)))
                ), swaps as (
                    select s.requester_date,s.receiver_date from timetable_swap_requests s
                    join timetable t on t.id=s.requester_timetable_id where s.school_id=?
                        and t.academic_year=? and t.semester=?
                )
                select origin_date from dated union select teaching_date from dated
                union select requester_date from swaps union select receiver_date from swaps
                union select s.substitute_date from substitute_requests s join timetable t on t.id=s.timetable_id
                    where s.school_id=? and t.academic_year=? and t.semester=?
                """, LocalDate.class, term.termId(), school, term.academicYear(), term.semester(),
                school, term.academicYear(), term.semester()));
    }
}
