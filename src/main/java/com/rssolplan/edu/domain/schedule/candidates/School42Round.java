package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.schedule.operations.ScheduleConflict;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** The marker is acquired before every rehearsal write. Normal schools never receive one. */
@Service
@RequiredArgsConstructor
public class School42Round {
    static final long ADMIN = 541000, TEACHER = 541004, SCHOOL = 540001, TERM = 547001;
    private final JdbcTemplate db;
    private final RehearsalApplicant applicant;

    public enum Mode { TIMETABLE, FULL }
    private static final String LESSONS = """
            from dated_lesson d join weekly_lesson w on w.id=d.origin_id
            join schedule_version v on v.id=w.version_id where v.term_id=547001
            """;
    private static final String NOTIFICATIONS = """
            from notifications n join lesson_change c on n.target_id=c.id
            where n.school_id=540001 and n.target_type='LESSON_CHANGE' and c.term_id=547001
            """;


    public void prepareTable() {
        db.execute("""
                create table if not exists school42_rehearsal (
                  school_id bigint primary key, round_no bigint not null, validated_load char(36)
                ) engine=InnoDB
                """);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void open() {
        db.update("insert into school42_rehearsal(school_id,round_no) values (?,0) on duplicate key update school_id=school_id", SCHOOL);
        long previous = round(true);
        if (previous <= 0) {
            if (count("select count(*) from school where id=540001") != 0 ||
                    count("select count(*) from timetable_set where id=547001") != 0)
                throw conflict("지정한 ID가 이미 사용 중이므로 시연 명부를 생성할 수 없습니다.");
            script("fixture");
            db.update("update school42_rehearsal set round_no=? where school_id=?", Math.max(1L, -previous), SCHOOL);
        }
        verifyIdentity();
    }

    public record Setup(TimetableScope scope, String schoolCode) {}

    @Transactional(readOnly = true)
    public Setup setupStatus() {
        if (count("select count(*) from information_schema.tables where table_schema=database() and table_name='school42_rehearsal'") == 0 ||
                count("select count(*) from school42_rehearsal where school_id=540001 and round_no>0") == 0)
            return new Setup(null, null);
        return new Setup(timetableScope(), schoolCode());
    }

    private String schoolCode() {
        return db.queryForObject("select school_code from school where id=?", String.class, SCHOOL);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Setup prepare() {
        open();
        db.queryForObject("select id from school where id=? for update", Long.class, SCHOOL);
        String code = schoolCode();
        if (code == null || code.isBlank()) {
            code = com.rssolplan.edu.global.fordevToken.SchoolCodeGenerator.generate();
            db.update("update school set school_code=? where id=?", code, SCHOOL);
        }
        return new Setup(scope(round(false), Mode.TIMETABLE, false), code);
    }

    long round(boolean lock) {
        return db.queryForObject("select round_no from school42_rehearsal where school_id=?" +
                (lock ? " for update" : ""), Long.class, SCHOOL);
    }

    void verifyIdentity() {
        if (count("""
            select count(*) from school s join timetable_set t on t.school_id=s.id
            join school_user m on m.school_id=s.id join users u on u.id=m.user_id and u.active_school_id=s.id
            where s.id=540001 and s.name='시연한들고등학교' and t.id=547001 and t.academic_year=2026
              and t.semester=2 and m.employment_status='HIRED' and
              ((m.id=542000 and u.id=541000 and u.email='admin42@example.invalid' and m.position='ADMIN')
               or (m.id=542004 and u.id=541004 and u.email='teacher42-04@example.invalid' and m.position='TEACHER'))
            """) != 2) throw conflict("시연 학교·학기·계정 연결을 확인하세요.");
    }

    void requireRound(long expected) {
        if (expected <= 0 || round(true) != expected) throw conflict("다른 팀원이 회차를 초기화했습니다. 다시 입장하세요.");
        verifyIdentity();
    }

    void retain(UUID validated) {
        db.update("update school42_rehearsal set validated_load=? where school_id=?", validated.toString(), SCHOOL);
    }

    void requireLoad(UUID id) {
        if (id == null || !id.toString().equals(db.queryForObject(
                "select validated_load from school42_rehearsal where school_id=?", String.class, SCHOOL)))
            throw conflict("이 회차에서 시수표를 다시 검증하세요.");
    }

    public record TimetableScope(@Positive long schoolId, @Positive long termId, @Positive long round,
            @PositiveOrZero long jobs, @PositiveOrZero long versions, Mode mode, long candidates,
            long weeklyLessons, long datedLessons, long requests, long notifications, long receipts,
            RehearsalApplicant.Target applicant) {}

    private TimetableScope scope(long revision, Mode mode, boolean lock) {
        return new TimetableScope(SCHOOL, TERM, revision,
                count("select count(*) from generation_job where school_id=540001 and term_id=547001"),
                count("select count(*) from schedule_version where term_id=547001"), mode,
                count("select coalesce(sum(json_length(result,'$.candidates')),0) from generation_job where school_id=540001 and term_id=547001"),
                count("select count(*) from weekly_lesson w join schedule_version v on v.id=w.version_id where v.term_id=547001"),
                count("select count(*) " + LESSONS),
                count("select count(*) from lesson_change where term_id=547001"),
                count("select count(*) " + NOTIFICATIONS),
                count("select count(*) from publication_receipt r join schedule_version v on v.id=r.version_id where v.term_id=547001"),
                mode == Mode.FULL ? applicant.inspect(lock) : null);
    }

    @Transactional(readOnly = true)
    public TimetableScope timetableScope() {
        return timetableScope(Mode.TIMETABLE);
    }

    @Transactional(readOnly = true)
    public TimetableScope timetableScope(Mode mode) {
        verifyIdentity();
        if (count("select count(*) from information_schema.tables where table_schema=database() and table_name='school42_rehearsal'") == 0 ||
                count("select count(*) from school42_rehearsal where school_id=540001 and round_no>0") == 0)
            throw conflict("시연 학교와 회차를 먼저 준비하세요.");
        return scope(round(false), mode, false);
    }

    private void lockReset(long expected) {
        requireRound(expected);
        db.queryForObject("select id from school where id=? for update", Long.class, SCHOOL);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TimetableScope resetTimetables(TimetableScope expected) {
        lockReset(expected.round());
        if (expected.mode() == null || !scope(expected.round(), expected.mode(), true).equals(expected))
            throw conflict("초기화할 시간표가 바뀌었습니다. 현재 범위를 다시 확인하세요.");
        ensureResettable();
        db.update("delete n " + NOTIFICATIONS);
        db.update("delete from lesson_change where term_id=?", TERM);
        long next = clearTimetables(expected.round());
        if (expected.mode() == Mode.FULL) applicant.clear(expected.applicant());
        return scope(next, expected.mode(), false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long reset(long expected) {
        lockReset(expected);
        if (count("select count(*) from lesson_change where term_id=547001") != 0)
            throw conflict("보결·교환이 있는 회차는 운영자 확인이 필요합니다.");
        ensureResettable();
        return clearTimetables(expected);
    }

    private void ensureResettable() {
        if (count("select count(*) from generation_job where school_id=540001 and term_id=547001 and state in ('QUEUED','RUNNING')") != 0)
            throw conflict("편성이 끝난 뒤 초기화하세요.");
        if (count("""
                    select count(*) from lesson_change c
                    left join dated_lesson a on a.id=c.first_id left join weekly_lesson aw on aw.id=a.origin_id
                    left join schedule_version av on av.id=aw.version_id
                    left join dated_lesson b on b.id=c.second_id left join weekly_lesson bw on bw.id=b.origin_id
                    left join schedule_version bv on bv.id=bw.version_id
                    where (c.term_id=547001 and (av.term_id<>547001 or bv.term_id<>547001))
                    or (c.term_id<>547001 and (av.term_id=547001 or bv.term_id=547001))
                    """) != 0 || count("""
                    select count(*) from legacy_approval_input a join dated_lesson d on d.id=a.dated_id
                    join weekly_lesson w on w.id=d.origin_id join schedule_version v on v.id=w.version_id
                    where v.term_id=547001
                    """) != 0 || count("""
                    select count(*) from notifications n join lesson_change c on n.target_id=c.id
                    where n.target_type='LESSON_CHANGE' and c.term_id=547001 and (n.school_id is null or n.school_id<>540001)
                    """) != 0 || count("""
                    select count(*) from publication_receipt r join generation_job j on j.id=r.job_id
                    join schedule_version v on v.id=r.version_id
                    where (v.term_id=547001 and (j.school_id<>540001 or j.term_id is null or j.term_id<>547001))
                       or (j.school_id=540001 and j.term_id=547001 and v.term_id<>547001)
                    """) != 0) throw conflict("별도의 승인·변경 기록이 연결되어 초기화를 중단했습니다.");
    }

    private long clearTimetables(long expected) {
        script("reset");
        db.update("update school42_rehearsal set round_no=round_no+1 where school_id=?", SCHOOL);
        return expected + 1;
    }

    long count(String sql) { return db.queryForObject(sql, Long.class); }

    private void script(String name) {
        var resource = new EncodedResource(new ClassPathResource("school42/" + name + ".sql"), StandardCharsets.UTF_8);
        db.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, resource);
            return null;
        });
    }

    private static ScheduleConflict conflict(String message) {
        return new ScheduleConflict("SCHOOL42_ROUND", null, message);
    }
}
