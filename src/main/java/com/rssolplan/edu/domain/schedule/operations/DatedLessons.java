package com.rssolplan.edu.domain.schedule.operations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rssolplan.edu.domain.schedule.candidates.PublishedOperatingHours;
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
    private final PublishedOperatingHours operatingHours;

    public record Assignment(
            long teacherId, long periodId,
            @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean cancelled) {
        public Assignment(long teacherId, long periodId) {
            this(teacherId, periodId, false);
        }
    }

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
join timetable_set t on t.id=v.term_id
where d.teaching_date between t.start_date and t.end_date
and not exists(select 1 from term_excluded_date x where x.term_id=t.id and x.excluded_date in (d.origin_date,d.teaching_date))
and v.id=(select av.id from schedule_version av where av.term_id=v.term_id and av.effective_from<=d.origin_date
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

    List<Long> teachingMembers(long school) {
        return db.queryForList("""
                select m.id from school_user m where m.school_id=? and m.position='TEACHER'
                  and m.employment_status='HIRED' and not exists (
                    select 1 from school_teacher_roster r join roster_teaching_link l on l.roster_id=r.id
                    where r.school_user_id=m.id and r.school_id=m.school_id)
                order by m.id
                """, Long.class, school);
    }

    // Read both sides of the proposed movement from the effective dated timetable.
    public List<Long> candidates(long school, Lesson first, ChangeService.Kind kind, LocalDate targetDate) {
        List<Long> teachers = teachingMembers(school);
        SlotDay source = slots(school, first.termId, first.date);
        Set<Long> departing = Set.of(first.id);
        if (kind == ChangeService.Kind.SUBSTITUTE)
            return teachers.stream()
                    .filter(t -> t != first.actual.teacherId && source.fits(first.actual.periodId, t, first.classId, departing))
                    .toList();
        SlotDay target = first.date.equals(targetDate) ? source : slots(school, first.termId, targetDate);
        var hours = operatingHours.forVersion(first.versionId);
        return target.rows.stream().filter(second ->
                    !second.actual.cancelled && second.versionId == first.versionId
                    && second.actual.teacherId != first.actual.teacherId && teachers.contains(second.actual.teacherId)
                    && !(second.date.equals(first.date) && second.actual.periodId == first.actual.periodId)
                    && withinTeachingHours(hours, first, second)
                    && source.fits(first.actual.periodId, second.actual.teacherId, second.classId, departing)
                    && target.fits(second.actual.periodId, first.actual.teacherId, first.classId, Set.of(second.id)))
                .map(Lesson::id).toList();
    }

    private static boolean withinTeachingHours(Optional<PublishedOperatingHours.Calendar> hours,
                                                Lesson first, Lesson second) {
        return hours.isEmpty() || (hours.get().allows(first.classId, second.date, second.actual.periodId)
                && hours.get().allows(second.classId, first.date, first.actual.periodId));
    }

    record Unavailable(long teacher, long period) {}
    private record SlotDay(List<Lesson> rows, Set<Unavailable> unavailable) {
        boolean fits(long period, long teacher, long classId, Set<Long> departing) {
            return !unavailable.contains(new Unavailable(teacher, period)) && rows.stream().noneMatch(row ->
                    !departing.contains(row.id) && !row.actual.cancelled && row.actual.periodId == period
                    && (row.actual.teacherId == teacher || row.classId == classId));
        }
    }

    Set<Unavailable> unavailable(long school, LocalDate date) {
        return Set.copyOf(db.query("""
                select coalesce(t.id, m.id), ps.id from teacher_availability a
                join school_user m on m.id=a.school_user_id
                join school_setting ss on ss.school_id=m.school_id
                join period_setting ps on ps.school_setting_id=ss.id and ps.period_number=a.period_number
                left join school_teacher_roster r on r.school_user_id=m.id and r.school_id=m.school_id
                    and m.position='TEACHER' and m.employment_status='HIRED'
                left join roster_teaching_link l on l.roster_id=r.id
                left join school_user t on t.id=l.teacher_school_user_id and t.school_id=m.school_id
                    and t.position='TEACHER' and t.employment_status='HIRED'
                where m.school_id=? and a.day_of_week=?
                """, (r,n) -> new Unavailable(r.getLong(1),r.getLong(2)), school,
                date.getDayOfWeek().name().substring(0, 3)));
    }

    private SlotDay slots(long school, long term, LocalDate date) {
        return new SlotDay(day(term, date), unavailable(school, date));
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
            if (!withinTeachingHours(operatingHours.forVersion(a.versionId), a, b))
                throw new ScheduleConflict("SLOT_CONFLICT", a.rev, "두 학급의 학년별 운영 교시 안에서 교환하세요.");
            edits.add(new Edit(a, move(a, b)));
            edits.add(new Edit(b, move(b, a)));
        }
        Set<Long> changed = new HashSet<>();
        edits.forEach(e -> changed.add(e.before.id));
        Map<LocalDate, SlotDay> days = new HashMap<>();
        for (var edit : edits) {
            var next = edit.after;
            var destination = days.computeIfAbsent(next.date, date -> slots(school, term, date));
            var hired = db.queryForObject(
                    "select exists(select 1 from school_user where id=? and school_id=? and employment_status='HIRED')",
                    Boolean.class, next.actual.teacherId, school);
            if (!Boolean.TRUE.equals(hired)
                    || !destination.fits(next.actual.periodId, next.actual.teacherId, next.classId, changed))
                throw new ScheduleConflict("SLOT_CONFLICT", edit.before.rev,
                        "담당 교사·학급의 해당 교시에 수업 또는 불가 조건이 있습니다.");
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
        if (actual.actual.cancelled)
            throw new ScheduleConflict("LESSON_CANCELLED", actual.rev, "휴강이 확정된 날짜 수업입니다.");
        if (actual.rev != expected) throw stale(actual.rev);
    }

    static ScheduleConflict stale(long current) {
        return new ScheduleConflict("STALE_LESSON", current, "다른 승인이 수업을 변경했습니다. 최신 시간표를 확인하세요.");
    }
}
