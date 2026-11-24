package com.rssolplan.edu.domain.schedule.operations;

import com.rssolplan.edu.global.exception.*;

import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ChangeService {
    public enum Kind {
        SUBSTITUTE,
        SWAP
    }

    public record NewChange(
            @NotNull Kind kind,
            @Positive long firstId,
            @NotNull @PositiveOrZero Long firstRev,
            @Positive Long secondId,
            @PositiveOrZero Long secondRev,
            @Positive Long substituteTeacherId) {}

    public record Change(
            long id,
            long termId,
            String kind,
            long requester,
            long responder,
            long firstId,
            long firstRev,
            Long secondId,
            Long secondRev,
            String state,
            long rev) {}

    public record Approval(
            @NotNull @PositiveOrZero Long expectedRequestRev,
            @NotNull @PositiveOrZero Long expectedLessonRev,
            @PositiveOrZero Long expectedSecondRev) {}

    public record Outcome(
            long requestId, String state, long requestRev, List<DatedLessons.Lesson> lessons) {}

    private final ScheduleAccess access;
    private final DatedLessons lessons;
    private final JdbcTemplate db;
    private final ScheduleJson json;
    private static final org.springframework.jdbc.core.RowMapper<Change> MAPPER =
            (r, n) ->
                    new Change(
                            r.getLong("id"),
                            r.getLong("term_id"),
                            r.getString("kind"),
                            r.getLong("requester"),
                            r.getLong("responder"),
                            r.getLong("first_id"),
                            r.getLong("first_rev"),
                            r.getObject("second_id", Long.class),
                            r.getObject("second_rev", Long.class),
                            r.getString("state"),
                            r.getLong("rev"));

    Change get(long term, long id) {
        return db
                .query("select * from lesson_change where term_id=? and id=?", MAPPER, term, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("신청이 없습니다."));
    }

    public List<Change> list(long user, long term) {
        access.schoolOfTerm(user, term, false);
        var me = access.member(user, false);
        if (me.getPosition() == com.rssolplan.edu.domain.school.SchoolUser.Position.ADMIN)
            return db.query(
                    "select * from lesson_change where term_id=? order by id desc", MAPPER, term);
        return db.query(
                "select * from lesson_change where term_id=? and (requester=? or responder=?) order"
                    + " by id desc",
                MAPPER,
                term,
                me.getId(),
                me.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Change create(long user, long term, NewChange q) {
        boolean cover = q.kind == Kind.SUBSTITUTE;
        long school = access.schoolOfTerm(user, term, cover);
        access.lockSchool(school);
        var me = access.member(user, cover);
        var a = lessons.get(term, q.firstId);
        DatedLessons.check(a, q.firstRev);
        long responder;
        if (cover) {
            if (q.secondId != null
                    || q.secondRev != null
                    || q.substituteTeacherId == null
                    || q.substituteTeacherId == a.actual().teacherId())
                throw new BadRequestException("다른 보결 교사를 지정하세요.");
            responder = q.substituteTeacherId;
        } else {
            if (q.secondId == null
                    || q.secondRev == null
                    || q.substituteTeacherId != null
                    || q.firstId == q.secondId) throw new BadRequestException("두 날짜 수업을 지정하세요.");
            if (me.getId() != a.actual().teacherId())
                throw new ForbiddenException("내 수업만 교환 신청할 수 있습니다.");
            var b = lessons.get(term, q.secondId);
            DatedLessons.check(b, q.secondRev);
            responder = b.actual().teacherId();
            if (responder == me.getId()) throw new BadRequestException("다른 담당자의 수업을 선택하세요.");
        }
        if (db.queryForList(
                        "select id from school_user where school_id=? and id=? and"
                            + " employment_status='HIRED'",
                        Long.class,
                        school,
                        responder)
                .isEmpty()) throw new NotFoundException("재직 중인 교사가 없습니다.");
        db.update(
                "insert into"
                    + " lesson_change(term_id,kind,requester,responder,first_id,first_rev,second_id,second_rev,state)"
                    + " values(?,?,?,?,?,?,?,?,'PENDING')",
                term,
                q.kind.name(),
                me.getId(),
                responder,
                a.id(),
                q.firstRev,
                q.secondId,
                q.secondRev);
        long id = db.queryForObject("select last_insert_id()", Long.class);
        notify(school, user, responder, q.kind.name(), false, "수업 변경 응답 요청 #" + id);
        return get(term, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Change respond(long user, long term, long id, boolean accept, long expected) {
        long school = access.schoolOfTerm(user, term, false);
        access.lockSchool(school);
        var c = get(term, id);
        if (c.responder != access.member(user, false).getId())
            throw new ForbiddenException("지정된 교사만 응답할 수 있습니다.");
        transition(c, "PENDING", expected, accept ? "ACCEPTED" : "REJECTED");
        return get(term, id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Outcome approve(long user, long term, long id, Approval q, UUID key) {
        long school = access.schoolOfTerm(user, term, true);
        access.lockSchool(school);
        var c = get(term, id);
        String ref =
                "change/"
                        + id
                        + "/"
                        + q.expectedRequestRev
                        + "/"
                        + q.expectedLessonRev
                        + "/"
                        + q.expectedSecondRev;
        var prior = replay(school, user, key, ref, Outcome.class);
        if (prior != null) return prior;
        if (q.expectedLessonRev != c.firstRev || !Objects.equals(c.secondRev, q.expectedSecondRev))
            throw new ScheduleConflict(
                    "STALE_LESSON", lessons.get(term, c.firstId).rev(), "신청 당시 수업 버전과 일치하지 않습니다.");
        var edits =
                lessons.apply(
                        school, term, c.firstId, c.firstRev, c.secondId, c.secondRev, c.responder);
        transition(c, "ACCEPTED", q.expectedRequestRev, "APPROVED");
        for (long person : new HashSet<>(List.of(c.requester, c.responder)))
            notify(school, user, person, c.kind, true, "수업 변경 승인 #" + id);
        var outcome =
                new Outcome(
                        id,
                        "APPROVED",
                        c.rev + 1,
                        edits.stream().map(DatedLessons.Edit::after).toList());
        record(school, user, key, ref, outcome, edits);
        return outcome;
    }

    private void transition(Change c, String from, long expected, String to) {
        if (db.update(
                        "update lesson_change set state=?,rev=rev+1 where id=? and state=? and"
                            + " rev=?",
                        to,
                        c.id,
                        from,
                        expected)
                != 1) throw new ScheduleConflict("STALE_REQUEST", c.rev, "신청의 상태·버전이 변경되었습니다.");
    }

    public <T> T replay(long school, long user, UUID key, String ref, Class<T> type) {
        if (key == null) return null;
        return db.query(
                "select request_ref,response from lesson_approval where school_id=? and actor=? and"
                    + " key_id=?",
                r -> {
                    if (!r.next()) return null;
                    if (!ref.equals(r.getString(1)))
                        throw new ScheduleConflict(
                                "IDEMPOTENCY_KEY_REUSED", null, "다른 승인에 사용한 키입니다.");
                    return json.read(r.getString(2), type);
                },
                school,
                user,
                key.toString());
    }

    public void record(
            long school, long user, UUID key, String ref, Object response, Object edits) {
        db.update(
                "insert into"
                    + " lesson_approval(school_id,actor,key_id,request_ref,response,changes_json)"
                    + " values(?,?,?,?,?,?)",
                school,
                user,
                (key == null ? UUID.randomUUID() : key).toString(),
                ref,
                json.write(response),
                json.write(edits));
    }

    private void notify(
            long school,
            long actor,
            long recipient,
            String kind,
            boolean approved,
            String message) {
        boolean cover = kind.equals("SUBSTITUTE");
        String type =
                cover
                        ? (approved
                                ? "SUBSTITUTE_ADMIN_APPROVED_TEACHER"
                                : "SUBSTITUTE_REQUEST_INVITE")
                        : (approved
                                ? "TIMETABLE_SWAP_ADMIN_APPROVED_RECEIVER"
                                : "TIMETABLE_SWAP_REQUEST");
        db.update(
                """
insert into notifications(user_id,school_id,requester_id,category,type,message,is_read,created_at)
select user_id,?,?,?, ?,?,false,utc_timestamp(6) from school_user where id=? and school_id=?
""",
                school,
                actor,
                cover ? "SUBSTITUTE" : "TIMETABLE_SWAP",
                type,
                message,
                recipient,
                school);
    }
}
