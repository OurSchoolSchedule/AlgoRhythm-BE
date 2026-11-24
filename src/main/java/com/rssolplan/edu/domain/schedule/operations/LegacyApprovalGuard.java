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

    public void capture(String kind, long request, Target... targets) {
        for (int i = 0; i < targets.length; i++) {
            var b = current(targets[i]);
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

    public List<?> approve(
            long school, String kind, long request, long substitute, Target... targets) {
        List<Binding> bound = new ArrayList<>();
        for (int i = 0; i < targets.length; i++) {
            var now = current(targets[i]);
            var rows =
                    db.query(
                            "select dated_id,expected_rev from legacy_approval_input where kind=?"
                                + " and request_id=? and side_no=?",
                            (r, n) ->
                                    new Binding(r.getObject(1, Long.class), r.getLong(2), now.term),
                            kind,
                            request,
                            i);
            var original = rows.isEmpty() ? new Binding(null, 0, 0) : rows.get(0);
            if (!Objects.equals(now.id, original.id) || original.rev != now.rev)
                throw DatedLessons.stale(now.rev);
            bound.add(now);
        }
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
