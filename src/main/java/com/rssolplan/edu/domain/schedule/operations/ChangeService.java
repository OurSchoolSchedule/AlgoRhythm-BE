package com.rssolplan.edu.domain.schedule.operations;

import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.global.exception.*;
import java.time.LocalDate;

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
            @Positive Long substituteTeacherId,
            LocalDate firstDate,
            LocalDate secondDate) {
        public NewChange(Kind kind, long firstId, Long firstRev, Long secondId, Long secondRev, Long substituteTeacherId) {
            this(kind, firstId, firstRev, secondId, secondRev, substituteTeacherId, null, null);
        }
    }

    public record CoverBatch(@Positive long firstId, @NotNull @PositiveOrZero Long firstRev,
            @NotEmpty List<@NotNull @Positive Long> substituteTeacherIds, LocalDate firstDate) {
        public CoverBatch(long firstId, Long firstRev, List<Long> substituteTeacherIds) {
            this(firstId, firstRev, substituteTeacherIds, null);
        }
    }

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
        if (me.getPosition() == SchoolUser.Position.ADMIN)
            return db.query(
                    "select * from lesson_change where term_id=? order by id desc", MAPPER, term);
        long teachingId = actorId(me);
        return db.query(
                "select * from lesson_change where term_id=? and (requester=? or responder=?) order"
                    + " by id desc",
                MAPPER,
                term,
                teachingId,
                teachingId);
    }

    public List<DatedLessons.Lesson> details(long user, long term, long id) {
        access.schoolOfTerm(user, term, false);
        var me = access.member(user, false);
        var change = get(term, id);
        long teacher = actorId(me);
        if (me.getPosition() != SchoolUser.Position.ADMIN && teacher != change.requester && teacher != change.responder)
            throw new ForbiddenException("본인의 신청만 조회할 수 있습니다.");
        var first = lessons.get(term, change.firstId);
        return change.secondId == null ? List.of(first) : List.of(first, lessons.get(term, change.secondId));
    }

    public List<Long> candidates(long user, long term, long firstId, long rev, Kind kind, LocalDate date) {
        long school = access.schoolOfTerm(user, term, false);
        var first = lessons.get(term, firstId);
        checkSource(access.member(user, false), first, rev, kind);
        return lessons.candidates(school, first, kind, date == null ? first.date() : date);
    }

    private long actorId(SchoolUser member) {
        long id = access.teachingId(member);
        if (access.recipientUserId(member.getSchool().getId(), id) != member.getUser().getId())
            throw new ForbiddenException("이 명부에 연결된 계정으로 로그인하세요.");
        return id;
    }

    private void checkSource(SchoolUser me, DatedLessons.Lesson first, long rev, Kind kind) {
        if (!(kind == Kind.SUBSTITUTE && me.getPosition() == SchoolUser.Position.ADMIN)
                && (me.getPosition() != SchoolUser.Position.TEACHER || actorId(me) != first.actual().teacherId()))
            throw new ForbiddenException("내가 담당하는 수업만 신청할 수 있습니다.");
        DatedLessons.check(first, rev);
    }

    private static void checkReviewedDate(DatedLessons.Lesson lesson, LocalDate reviewedDate) {
        if (reviewedDate != null && !reviewedDate.equals(lesson.date()))
            throw new ScheduleConflict("LESSON_DATE_CHANGED", lesson.rev(), "확인한 날짜와 실제 수업 날짜가 다릅니다. 수업을 다시 선택하세요.");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Change> createBatch(long user, long term, CoverBatch q) {
        long school = access.schoolOfTerm(user, term, false);
        access.lockSchool(school);
        var first = lessons.get(term, q.firstId);
        var me = access.member(user, false);
        checkSource(me, first, q.firstRev, Kind.SUBSTITUTE);
        checkReviewedDate(first, q.firstDate);
        var targets = new LinkedHashSet<>(q.substituteTeacherIds);
        if (targets.isEmpty() || targets.size() != q.substituteTeacherIds.size())
            throw new BadRequestException("중복되지 않은 보결 교사를 선택하세요.");
        var available = lessons.candidates(school, first, Kind.SUBSTITUTE, first.date());
        for (long target : targets) {
            if (!available.contains(target)) {
                requireHiredTeacher(school, target);
                throw new ScheduleConflict("SLOT_CONFLICT", first.rev(), "같은 학교의 공강 교사를 다시 선택하세요.");
            }
        }
        if (!db.queryForList("""
                select id from lesson_change where term_id=? and first_id=? and first_rev=?
                and kind='SUBSTITUTE' and state in ('PENDING','ACCEPTED') limit 1
                """, Long.class, term, first.id(), first.rev()).isEmpty())
            throw new ScheduleConflict("DUPLICATE_REQUEST", first.rev(), "이미 진행 중인 보결 요청이 있습니다.");
        long requester = actorId(me);
        return targets.stream().map(t -> insert(user, school, term, Kind.SUBSTITUTE, requester, t, first, null)).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Change create(long user, long term, NewChange q) {
        if (q.kind == Kind.SUBSTITUTE) {
            if (q.secondId != null || q.secondRev != null || q.secondDate != null || q.substituteTeacherId == null)
                throw new BadRequestException("다른 보결 교사를 지정하세요.");
            return createBatch(user, term, new CoverBatch(q.firstId, q.firstRev, List.of(q.substituteTeacherId), q.firstDate)).get(0);
        }
        if (q.secondId == null || q.secondRev == null || q.substituteTeacherId != null || q.firstId == q.secondId)
            throw new BadRequestException("두 날짜 수업을 지정하세요.");
        long school = access.schoolOfTerm(user, term, false);
        access.lockSchool(school);
        var me = access.member(user, false);
        var first = lessons.get(term, q.firstId);
        checkSource(me, first, q.firstRev, Kind.SWAP);
        checkReviewedDate(first, q.firstDate);
        var second = lessons.get(term, q.secondId);
        DatedLessons.check(second, q.secondRev);
        checkReviewedDate(second, q.secondDate);
        if (first.versionId() != second.versionId()
                || first.date().equals(second.date()) && first.actual().periodId() == second.actual().periodId())
            throw new BadRequestException("같은 시간표 버전의 서로 다른 교시를 선택하세요.");
        if (first.actual().teacherId() == second.actual().teacherId())
            throw new BadRequestException("다른 담당자의 수업을 선택하세요.");
        requireHiredTeacher(school, second.actual().teacherId());
        if (!lessons.candidates(school, first, Kind.SWAP, second.date()).contains(second.id()))
            throw new ScheduleConflict("SLOT_CONFLICT", first.rev(), "상대 교사와 학급 모두 교환 가능한 수업을 선택하세요.");
        if (!db.queryForList("""
                select id from lesson_change where term_id=? and kind='SWAP' and state in ('PENDING','ACCEPTED')
                 and ((first_id=? and first_rev=? and second_id=? and second_rev=?)
                   or (first_id=? and first_rev=? and second_id=? and second_rev=?)) limit 1
                """, Long.class, term, first.id(), first.rev(), second.id(), second.rev(),
                second.id(), second.rev(), first.id(), first.rev()).isEmpty())
            throw new ScheduleConflict("DUPLICATE_REQUEST", first.rev(), "같은 두 수업의 교환 요청이 이미 있습니다.");
        return insert(user, school, term, Kind.SWAP, actorId(me), second.actual().teacherId(), first, second);
    }

    private void requireHiredTeacher(long school, long teachingId) {
        if (db.queryForList("""
                select id from school_user where school_id=? and id=?
                and employment_status='HIRED' and position='TEACHER'
                """, Long.class, school, teachingId).isEmpty())
            throw new NotFoundException("재직 중인 교사가 없습니다.");
    }

    private Change insert(long user, long school, long term, Kind kind, long requester, long responder,
                          DatedLessons.Lesson first, DatedLessons.Lesson second) {
        db.update("""
                insert into lesson_change(term_id,kind,requester,responder,first_id,first_rev,second_id,second_rev,state)
                values(?,?,?,?,?,?,?,?,'PENDING')
                """, term, kind.name(), requester, responder, first.id(), first.rev(),
                second == null ? null : second.id(), second == null ? null : second.rev());
        long id = db.queryForObject("select last_insert_id()", Long.class);
        var change = get(term, id);
        notify(school, user, responder, change, kind == Kind.SUBSTITUTE ? "SUBSTITUTE_REQUEST_INVITE" : "TIMETABLE_SWAP_REQUEST",
                "수업 변경 응답 요청 #" + id);
        return change;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Change respond(long user, long term, long id, boolean accept, long expected) {
        long school = access.schoolOfTerm(user, term, false);
        access.lockSchool(school);
        var c = get(term, id);
        var me = access.member(user, false);
        if (me.getPosition() != SchoolUser.Position.TEACHER || c.responder != actorId(me))
            throw new ForbiddenException("지정된 교사만 응답할 수 있습니다.");
        transition(c, "PENDING", expected, accept ? "ACCEPTED" : "REJECTED");
        if (accept) {
            var first = lessons.get(term, c.firstId);
            DatedLessons.check(first, c.firstRev);
            var kind = Kind.valueOf(c.kind);
            var second = c.secondId == null ? null : lessons.get(term, c.secondId);
            if (second != null) DatedLessons.check(second, c.secondRev);
            if (!lessons.candidates(school, first, kind, second == null ? first.date() : second.date())
                    .contains(second == null ? c.responder : second.id()))
                throw new ScheduleConflict("SLOT_CONFLICT", first.rev(), "현재 시간표에서 다시 선택하세요.");
            if (kind == Kind.SUBSTITUTE) {
                for (var sibling : db.query("""
                        select * from lesson_change where term_id=? and first_id=? and first_rev=?
                        and kind='SUBSTITUTE' and state='PENDING'
                        """, MAPPER, term, c.firstId, c.firstRev)) {
                    transition(sibling, "PENDING", sibling.rev, "WITHDRAWN");
                    notify(school, user, sibling.responder, sibling, "SUBSTITUTE_FILLED_BROADCAST", "다른 교사가 보결을 수락했습니다. #" + sibling.id);
                }
            }
            for (long admin : db.queryForList("select id from school_user where school_id=? and position='ADMIN' and employment_status='HIRED'", Long.class, school))
                notify(school, user, admin, c, kind == Kind.SUBSTITUTE ? "SUBSTITUTE_NOTIFY_ADMIN" : "TIMETABLE_SWAP_NOTIFY_ADMIN",
                        "수업 변경 승인 대기 #" + c.id);
        }
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
            notify(school, user, person, c, c.kind.equals("SUBSTITUTE") ? "SUBSTITUTE_ADMIN_APPROVED_TEACHER" : "TIMETABLE_SWAP_ADMIN_APPROVED_RECEIVER", "수업 변경 승인 #" + id);
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

    private void notify(long school, long actor, long recipient, Change change, String type, String message) {
        db.update("""
                insert into notifications(user_id,school_id,requester_id,category,type,target_type,target_id,message,is_read,created_at)
                values(?,?,?,?,?,'LESSON_CHANGE',?,?,false,utc_timestamp(6))
                """, access.recipientUserId(school, recipient), school, actor,
                change.kind.equals("SUBSTITUTE") ? "SUBSTITUTE" : "TIMETABLE_SWAP",
                type, change.id, message);
    }
}
