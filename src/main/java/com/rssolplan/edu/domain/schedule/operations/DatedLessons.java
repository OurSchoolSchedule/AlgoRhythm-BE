package com.rssolplan.edu.domain.schedule.operations;

import com.rssolplan.edu.global.exception.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DatedLessons {
    private final JdbcTemplate db;
    private final ScheduleAccess access;
    private final ScheduleJson json;

    public record Assignment(long teacherId, long periodId) {}

    public record Lesson(
            long id,
            long originId,
            long termId,
            long versionId,
            LocalDate originDate,
            LocalDate date,
            long classId,
            long subjectId,
            Assignment planned,
            Assignment actual,
            long rev) {}

    public record Edit(Lesson before, Lesson after) {}

    private static final String SELECT =
            """
select d.*,w.class_id,w.subject_id,w.version_id,v.term_id from dated_lesson d
join weekly_lesson w on w.id=d.origin_id join schedule_version v on v.id=w.version_id
where v.id=(select av.id from schedule_version av where av.term_id=v.term_id and av.effective_from<=d.origin_date
            order by av.version_no desc limit 1)
""";

    private List<Lesson> find(String filter, Object... args) {
        return db.query(
                SELECT + filter,
                (r, n) ->
                        new Lesson(
                                r.getLong("id"),
                                r.getLong("origin_id"),
                                r.getLong("term_id"),
                                r.getLong("version_id"),
                                r.getDate("origin_date").toLocalDate(),
                                r.getDate("teaching_date").toLocalDate(),
                                r.getLong("class_id"),
                                r.getLong("subject_id"),
                                json.read(r.getString("planned"), Assignment.class),
                                json.read(r.getString("actual"), Assignment.class),
                                r.getLong("rev")),
                args);
    }

    public List<Lesson> date(long user, long term, LocalDate date) {
        access.schoolOfTerm(user, term, false);
        return day(term, date);
    }

    List<Lesson> day(long term, LocalDate date) {
        return find(" and v.term_id=? and d.teaching_date=? order by d.id", term, date);
    }

    public Lesson get(long term, long id) {
        return find(" and v.term_id=? and d.id=?", term, id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("현재 버전의 날짜 수업이 없습니다."));
    }

    // Caller holds the school guard inside the same READ COMMITTED transaction as request state and
    // notifications.
    public List<Edit> apply(
            long school,
            long term,
            long first,
            long firstRev,
            Long second,
            Long secondRev,
            long substitute) {
        var a = get(term, first);
        check(a, firstRev);
        List<Edit> edits = new ArrayList<>();
        if (second == null) {
            if (a.actual.teacherId == substitute) throw new BadRequestException("이미 수업 담당자입니다.");
            edits.add(
                    new Edit(
                            a,
                            after(
                                    a,
                                    a.date,
                                    a.planned,
                                    new Assignment(substitute, a.actual.periodId))));
        } else {
            var b = get(term, second);
            check(b, secondRev);
            if (first == second
                    || a.versionId != b.versionId
                    || a.date.equals(b.date) && a.actual.periodId == b.actual.periodId)
                throw new BadRequestException("같은 시간표 버전의 서로 다른 교시를 선택하세요.");
            edits.add(new Edit(a, move(a, b)));
            edits.add(new Edit(b, move(b, a)));
        }
        Set<Long> changed = new HashSet<>();
        edits.forEach(e -> changed.add(e.before.id));
        Map<LocalDate, List<Lesson>> days = new HashMap<>();
        for (var edit : edits) {
            var next = edit.after;
            var others =
                    days.computeIfAbsent(
                            next.date,
                            date ->
                                    new ArrayList<>(
                                            day(term, date).stream()
                                                    .filter(l -> !changed.contains(l.id))
                                                    .toList()));
            validateSlot(school, next, others);
            others.add(next);
        }
        edits.sort(Comparator.comparingLong(e -> e.before.id));
        for (var edit : edits) {
            var before = edit.before;
            var next = edit.after;
            if (db.update(
                            "update dated_lesson set teaching_date=?,planned=?,actual=?,rev=rev+1"
                                + " where id=? and rev=?",
                            next.date,
                            json.write(next.planned),
                            json.write(next.actual),
                            before.id,
                            before.rev)
                    != 1) throw stale(get(term, before.id).rev);
        }
        return edits;
    }

    private void validateSlot(long school, Lesson next, List<Lesson> others) {
        long t = next.actual.teacherId, p = next.actual.periodId;
        boolean unavailable =
                db.queryForObject(
                        """
select not exists(select 1 from school_user where id=? and school_id=? and employment_status='HIRED')
    or exists(select 1 from teacher_availability a join period_setting ps on ps.period_number=a.period_number
             where ps.id=? and a.school_user_id=? and a.day_of_week=?)
""",
                        Boolean.class,
                        t,
                        school,
                        p,
                        t,
                        next.date.getDayOfWeek().name().substring(0, 3));
        if (unavailable
                || others.stream()
                        .anyMatch(
                                l ->
                                        l.actual.periodId == p
                                                && (l.actual.teacherId == t
                                                        || l.classId == next.classId)))
            throw new ScheduleConflict(
                    "SLOT_CONFLICT", next.rev - 1, "담당 교사·학급의 해당 교시에 수업 또는 불가 조건이 있습니다.");
    }

    private static Lesson move(Lesson a, Lesson target) {
        return after(
                a,
                target.date,
                new Assignment(a.planned.teacherId, target.actual.periodId),
                new Assignment(a.actual.teacherId, target.actual.periodId));
    }

    private static Lesson after(Lesson a, LocalDate date, Assignment planned, Assignment actual) {
        return new Lesson(
                a.id,
                a.originId,
                a.termId,
                a.versionId,
                a.originDate,
                date,
                a.classId,
                a.subjectId,
                planned,
                actual,
                a.rev + 1);
    }

    public static void check(Lesson actual, long expected) {
        if (actual.rev != expected) throw stale(actual.rev);
    }

    static ScheduleConflict stale(long current) {
        return new ScheduleConflict("STALE_LESSON", current, "다른 승인이 수업을 변경했습니다. 최신 시간표를 확인하세요.");
    }
}
