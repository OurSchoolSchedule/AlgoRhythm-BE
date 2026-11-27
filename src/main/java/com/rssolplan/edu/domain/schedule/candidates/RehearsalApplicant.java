package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.schedule.operations.ScheduleConflict;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Only the live-join presenter is returned to the pre-join state. Timetable teacher IDs never move. */
@Component
@RequiredArgsConstructor
public class RehearsalApplicant {
    static final String EMAIL = "reslc0307@gmail.com";
    private final JdbcTemplate db;

    public record Target(long userId, Long memberId, Long rosterId, Long teachingId,
                  boolean pending, boolean activeSchool, boolean requestDetails) {}

    Target inspect(boolean lock) {
        String lockClause = lock ? " for update" : "";
        var users = db.queryForList("""
                select u.id from roster_verified_account a join users u on u.id=a.user_id
                where a.auth_email=?
                """ + lockClause, Long.class, EMAIL);
        if (users.size() != 1) throw conflict("교사 1이 이메일 인증을 완료했는지 확인하세요.");
        long user = users.get(0);
        var members = db.query("""
                select id,position,employment_status from school_user where school_id=? and user_id=?
                """ + lockClause, (rs, i) -> {
                    if (!"TEACHER".equals(rs.getString("position")) || !"HIRED".equals(rs.getString("employment_status")))
                        throw conflict("교사 1의 소속이 재직 교사가 아니므로 초기화할 수 없습니다.");
                    return rs.getLong(1);
                }, School42Round.SCHOOL, user);
        Long member = members.isEmpty() ? null : members.get(0);
        if (member != null && (member >= 542000 && member <= 542042 || hasPreservedReferences(member)))
            throw conflict("교사 1의 소속에 보존할 수업·명부·업무가 연결되어 초기화할 수 없습니다.");
        var entries = db.query("""
                select id, auth_email, pending_user_id, school_user_id from school_teacher_roster
                where school_id=? and (auth_email=? or pending_user_id=? or school_user_id=?)
                """ + lockClause, (r,i) -> {
                    Long pending = r.getObject(3, Long.class), linked = r.getObject(4, Long.class);
                    if (!EMAIL.equals(r.getString(2)) || (pending != null && pending != user)
                            || (linked != null && !linked.equals(member)) || (pending != null && linked != null))
                        throw conflict("교사 1의 가입 신청과 학교 소속이 서로 다릅니다.");
                    if (pending == null && linked == null)
                        throw conflict("사전 등록된 명부가 있습니다. 원본 명부 보호를 위해 운영자가 확인해야 합니다.");
                    return r.getLong(1);
                }, School42Round.SCHOOL, EMAIL, user, member);
        if (entries.size() > 1 || (member != null && entries.isEmpty()))
            throw conflict("교사 1의 소속과 가입 신청을 확인하세요.");
        Long roster = entries.isEmpty() ? null : entries.get(0);
        if (roster != null && db.queryForObject("select count(*) from roster_term where roster_id=?", Long.class, roster) > 0)
            throw conflict("교사 1의 신청 명부에 별도 학기 설정이 있어 보호를 위해 중단했습니다.");
        var links = db.query("""
                select t.id,t.school_id,t.position from roster_teaching_link l
                join school_user t on t.id=l.teacher_school_user_id where l.roster_id=?
                """ + lockClause, (r, i) -> {
                    long id = r.getLong("id");
                    if (r.getLong("school_id") != School42Round.SCHOOL || !"TEACHER".equals(r.getString("position"))
                            || id < 542001 || id > 542042)
                        throw conflict("다른 학교 또는 시연 명부 밖의 교사가 연결되어 초기화할 수 없습니다.");
                    return id;
                }, roster);
        boolean active = Boolean.TRUE.equals(db.queryForObject(
                "select coalesce(active_school_id=?,false) from users where id=?", Boolean.class, School42Round.SCHOOL, user));
        boolean details = Boolean.TRUE.equals(db.queryForObject(
                "select exists(select 1 from roster_join_details where roster_id=?)", Boolean.class, roster));
        return new Target(user, member, roster, links.isEmpty() ? null : links.get(0),
                roster != null && member == null, active, details);
    }

    // Keep every catalog and legacy-workflow dependency. Only the selected term's published
    // lessons and v2 requests will be removed in the same transaction as this membership.
    private boolean hasPreservedReferences(long member) {
        return Boolean.TRUE.equals(db.queryForObject("""
                select exists(select 1 from school_teacher_roster r where r.school_user_id=a.member_id and r.school_id<>a.school_id)
                    or exists(select 1 from roster_teaching_link l where l.teacher_school_user_id=a.member_id)
                    or exists(select 1 from school_class c where c.homeroom_teacher_id=a.member_id)
                    or exists(select 1 from teacher_availability t where t.school_user_id=a.member_id)
                    or exists(select 1 from timetable t where t.teacher_id=a.member_id)
                    or exists(select 1 from load_import l where l.created_by=a.member_id)
                    or exists(select 1 from load_demand d where d.teacher_id=a.member_id)
                    or exists(select 1 from substitute_requests s where s.owner_id=a.member_id
                        or s.receiver_user_ids regexp concat('(^|,)[[:space:]]*',a.member_id,'[[:space:]]*(,|$)'))
                    or exists(select 1 from substitute_responses s where s.candidate_id=a.member_id)
                    or exists(select 1 from timetable_swap_requests s where s.requester_id=a.member_id or s.receiver_id=a.member_id)
                    or exists(select 1 from lesson_change c where c.term_id<>a.term_id
                        and (c.requester=a.member_id or c.responder=a.member_id))
                    or exists(select 1 from weekly_lesson w join schedule_version v on v.id=w.version_id
                        where v.term_id<>a.term_id and w.teacher_id=a.member_id)
                    or exists(select 1 from dated_lesson d join weekly_lesson w on w.id=d.origin_id
                        join schedule_version v on v.id=w.version_id where v.term_id<>a.term_id
                        and (cast(json_unquote(json_extract(d.actual,'$.teacherId')) as unsigned)=a.member_id
                          or cast(json_unquote(json_extract(d.planned,'$.teacherId')) as unsigned)=a.member_id))
                from (select ? as member_id, ? as school_id, ? as term_id) a
                """, Boolean.class, member, School42Round.SCHOOL, School42Round.TERM));
    }

    void clear(Target target) {
        if (target.rosterId() != null) {
            // Removing this applicant row cascades only to its request details and teaching link.
            requireOne(db.update("""
                    delete from school_teacher_roster where id=? and school_id=? and auth_email=?
                    and (pending_user_id=? or school_user_id=?)
                    """, target.rosterId(), School42Round.SCHOOL, EMAIL, target.userId(), target.memberId()));
        }
        if (target.activeSchool()) requireOne(db.update(
                "update users set active_school_id=null where id=? and active_school_id=?", target.userId(), School42Round.SCHOOL));
        if (target.memberId() == null) return;
        try {
            requireOne(db.update("""
                delete from school_user where id=? and user_id=? and school_id=? and position='TEACHER' and employment_status='HIRED'
                """, target.memberId(), target.userId(), School42Round.SCHOOL));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw conflict("교사 1의 소속에 보호해야 하는 수업·업무가 연결되어 초기화를 취소했습니다.");
        }
    }

    private static void requireOne(int count) {
        if (count != 1) throw conflict("교사 1의 소속이 변경되었습니다. 다시 확인하세요.");
    }

    private static ScheduleConflict conflict(String message) {
        return new ScheduleConflict("SCHOOL42_APPLICANT", null, message);
    }
}
