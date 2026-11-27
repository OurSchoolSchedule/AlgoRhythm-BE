package com.rssolplan.edu.domain.schedule.operations;

import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.global.exception.BadRequestException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LegacyApprovalGuard {
    private final ScheduleAccess access;
    private final DatedLessons lessons;
    private final JdbcTemplate db;

    public long lockMember(long user, boolean admin) {
        long school = access.member(user, admin).getSchool().getId();
        access.lockSchool(school);
        return school;
    }

    public record Target(Timetable timetable, LocalDate date) {}

    private record Binding(Long id, long rev, long term) {}

    private Binding current(Target t) {
        var w = t.timetable;
        if (t.date == null || t.date.getDayOfWeek().getValue() != w.getDayOfWeek().getValue())
            throw new BadRequestException("원본 수업의 요일과 신청 날짜를 확인하세요.");
        boolean outsideCalendar = db.queryForObject("""
                select exists(select 1 from timetable_set t where t.school_id=? and t.academic_year=? and t.semester=?
                    and (? not between t.start_date and t.end_date or exists(select 1 from term_excluded_date x
                        where x.term_id=t.id and x.excluded_date=?)))
                """, Boolean.class, w.getSchool().getId(), w.getAcademicYear(), w.getSemester(), t.date, t.date);
        if (outsideCalendar)
            throw new ScheduleConflict("NON_TEACHING_DATE", null, "학기 기간과 정규수업 제외일을 확인하세요.");
        var terms =
                db.queryForList(
                        """
select t.id from timetable_set t where t.school_id=? and t.academic_year=? and t.semester=?
and ? between t.start_date and t.end_date and exists(select 1 from schedule_version v where v.term_id=t.id and v.effective_from<=?)
""",
                        Long.class,
                        w.getSchool().getId(),
                        w.getAcademicYear(),
                        w.getSemester(),
                        t.date,
                        t.date);
        if (terms.isEmpty()) {
            var r =
                    db.queryForList(
                            "select rev from legacy_lesson_rev where timetable_id=? and"
                                + " lesson_date=?",
                            Long.class,
                            w.getId(),
                            t.date);
            return new Binding(null, r.isEmpty() ? 0 : r.get(0), 0);
        }
        var term = terms.get(0);
        var lesson =
                lessons.day(term, t.date).stream()
                        .filter(
                                l ->
                                        l.classId() == w.getSchoolClass().getId()
                                                && l.actual().periodId()
                                                        == w.getPeriodSetting().getId()
                                                && l.actual().teacherId() == w.getTeacher().getId()
                                                && l.subjectId() == w.getSubject().getId())
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new ScheduleConflict(
                                                "STALE_LESSON", null, "현재 날짜 시간표에서 수업을 다시 선택하세요."));
        return new Binding(lesson.id(), lesson.rev(), term);
    }

    /** Public recruitment uses the same dated teachers as the direct-invitation workflow. */
    public List<Long> coverCandidates(Target target, Long requestId) {
        var current = requestId == null ? current(target) : checked("COVER", requestId, 0, target);
        var timetable = target.timetable;
        long school = timetable.getSchool().getId();
        if (current.id != null) {
            var lesson = lessons.get(current.term, current.id);
            DatedLessons.check(lesson, current.rev);
            return lessons.candidates(school, lesson, ChangeService.Kind.SUBSTITUTE, target.date);
        }
        long period = timetable.getPeriodSetting().getId();
        var busy = new HashSet<>(db.queryForList("""
                with slots as (
                    select id,teacher_id from timetable where school_id=? and academic_year=? and semester=?
                        and day_of_week=? and period_setting_id=?
                ), assignments as (
                    select id,teacher_id,null applied,0 change_id,0 kind from slots
                    union all
                    select s.id,r.candidate_id,r.updated_at,r.id,1 from slots s
                    join substitute_requests q on q.timetable_id=s.id and q.substitute_date=? and q.status='FILLED'
                    join substitute_responses r on r.substitute_request_id=q.id and r.manager_approval='APPROVED'
                    union all
                    select s.id,q.receiver_id,q.updated_at,q.id,2 from slots s
                    join timetable_swap_requests q on q.requester_timetable_id=s.id and q.requester_date=?
                        and q.manager_approval_status='APPROVED'
                    union all
                    select s.id,q.requester_id,q.updated_at,q.id,2 from slots s
                    join timetable_swap_requests q on q.receiver_timetable_id=s.id and q.receiver_date=?
                        and q.manager_approval_status='APPROVED'
                ), ranked as (
                    select teacher_id,row_number() over(partition by id order by applied desc,change_id desc,kind desc) rn
                    from assignments
                )
                select coalesce(l.teacher_school_user_id,a.teacher_id) from ranked a
                left join school_teacher_roster r on r.school_user_id=a.teacher_id and r.school_id=?
                left join roster_teaching_link l on l.roster_id=r.id where a.rn=1
                """, Long.class, school, timetable.getAcademicYear(), timetable.getSemester(),
                timetable.getDayOfWeek().name(), period, target.date, target.date, target.date, school));
        var unavailable = lessons.unavailable(school, target.date);
        return lessons.teachingMembers(school).stream()
                .filter(id -> !busy.contains(id) && !unavailable.contains(new DatedLessons.Unavailable(id, period)))
                .toList();
    }

    public void capture(String kind, long request, Target... targets) {
        var bindings = Arrays.stream(targets).map(this::current).toList();
        if ("SWAP".equals(kind)) requireSwap(bindings.get(0), bindings.get(1), targets[0].timetable.getSchool().getId());
        for (int i = 0; i < targets.length; i++) {
            var b = bindings.get(i);
            db.update(
                    "insert into"
                        + " legacy_approval_input(kind,request_id,side_no,dated_id,expected_rev)"
                        + " values(?,?,?,?,?)",
                    kind,
                    request,
                    i,
                    b.id,
                    b.rev);
        }
    }

    public void checkSwapResponse(long request, Target first, Target second) {
        requireSwap(checked("SWAP", request, 0, first), checked("SWAP", request, 1, second),
                first.timetable.getSchool().getId());
    }

    private void requireSwap(Binding a, Binding b, long school) {
        if (a.term != b.term) throw new BadRequestException("두 수업의 게시 범위가 다릅니다.");
        if (a.id == null) return;
        var first = lessons.get(a.term, a.id);
        var second = lessons.get(b.term, b.id);
        DatedLessons.check(first, a.rev);
        DatedLessons.check(second, b.rev);
        if (!lessons.candidates(school, first, ChangeService.Kind.SWAP, second.date()).contains(second.id()))
            throw new ScheduleConflict("SLOT_CONFLICT", a.rev, "두 학급과 교사가 모두 교환 가능한 교시를 선택하세요.");
    }

    private Binding checked(String kind, long request, int side, Target target) {
        var now = current(target);
        var rows =
                db.query(
                        "select dated_id,expected_rev from legacy_approval_input where kind=?"
                            + " and request_id=? and side_no=?",
                        (r, n) ->
                                new Binding(r.getObject(1, Long.class), r.getLong(2), now.term),
                        kind,
                        request,
                        side);
        var original = rows.isEmpty() ? new Binding(null, 0, 0) : rows.get(0);
        if (!Objects.equals(now.id, original.id) || original.rev != now.rev)
            throw DatedLessons.stale(now.rev);
        return now;
    }

    public List<?> approve(
            long school, String kind, long request, long substitute, Target... targets) {
        List<Binding> bound = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) bound.add(checked(kind, request, i, targets[i]));
        var a = bound.get(0);
        var b = bound.size() == 2 ? bound.get(1) : null;
        if (b != null && a.term != b.term) throw new BadRequestException("두 수업의 게시 범위가 다릅니다.");
        if (a.id != null)
            return lessons.apply(
                    school,
                    a.term,
                    a.id,
                    a.rev,
                    b == null ? null : b.id,
                    b == null ? null : b.rev,
                    substitute);
        List<Map<String, Object>> edits = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            var t = targets[i];
            long rev = bound.get(i).rev;
            db.update(
                    "insert into legacy_lesson_rev(timetable_id,lesson_date,rev) values(?,?,0) on"
                        + " duplicate key update rev=rev",
                    t.timetable.getId(),
                    t.date);
            if (db.update(
                            "update legacy_lesson_rev set rev=rev+1 where timetable_id=? and"
                                + " lesson_date=? and rev=?",
                            t.timetable.getId(),
                            t.date,
                            rev)
                    != 1) throw DatedLessons.stale(current(t).rev);
            edits.add(
                    Map.of(
                            "timetableId",
                            t.timetable.getId(),
                            "date",
                            t.date,
                            "previousRev",
                            rev,
                            "rev",
                            rev + 1));
        }
        return edits;
    }
}
