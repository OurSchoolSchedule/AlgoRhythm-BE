package com.rssolplan.edu.domain.roster;

import com.rssolplan.edu.domain.onboarding.dto.OnboardingResponse;
import com.rssolplan.edu.global.exception.BadRequestException;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;

@Service
@RequiredArgsConstructor
@Transactional
public class RosterMembershipService {
    private final JdbcTemplate db;
    private final TeacherRosterService roster;
    private final RosterMatchingService matching;

    private record School(long id, String code, String name, String address, String phone) {}
    private record Account(long id, Long activeSchoolId) {}
    private record Member(long id, String role, String employment, LocalDate hireDate) {}
    private record Entry(long id, Long memberId, Long pendingUserId) {}

    private static final RowMapper<School> SCHOOL = (rs, n) -> new School(rs.getLong("id"),
            rs.getString("school_code"), rs.getString("name"), rs.getString("address"), rs.getString("phone_number"));

    private static final RowMapper<Entry> ENTRY = (rs, n) -> new Entry(rs.getLong("id"),
            rs.getObject("school_user_id", Long.class), rs.getObject("pending_user_id", Long.class));

    /** Email verification reports existing membership; only the signed-in owner can confirm a name. */
    public OnboardingResponse existingMembership(long userId, Long activeSchoolId) {
        if (activeSchoolId == null) return null;
        School school = db.query("SELECT * FROM school WHERE id=?", SCHOOL, activeSchoolId)
                .stream().findFirst().orElse(null);
        Member member = member(userId, activeSchoolId);
        return school == null || member == null ? null : response(userId, school, member);
    }

    private record Applicant(School school, Account user, String email, Member member, Entry entry) {}

    private Applicant applicant(long userId, String code) {
        School school = lockSchoolByCode(code);
        Account user = lockAccount(userId);
        String email = verifiedAddress(userId);
        if (email == null) throw new ForbiddenException("학교 이름 등록 전에 이메일 인증이 필요합니다.");
        Member member = member(userId, school.id());
        if (member != null && !"HIRED".equals(member.employment()))
            throw new ForbiddenException("재직 중인 소속만 이름을 확인할 수 있습니다.");
        // Also recognize an existing linked row if an older account has a different historical roster address.
        var entries = db.query("""
                SELECT id, school_user_id, pending_user_id FROM school_teacher_roster
                WHERE school_id=? AND (auth_email=? OR school_user_id=? OR pending_user_id=?) FOR UPDATE
                """, ENTRY, school.id(), email, member == null ? null : member.id(), userId);
        if (entries.size() > 1) throw new IllegalStateException("소속과 인증 이메일의 명부가 다릅니다. 관리자에게 확인해 주세요.");
        Entry entry = entries.isEmpty() ? null : entries.get(0);
        if (entry != null && ((entry.memberId() != null && (member == null || entry.memberId() != member.id()))
                || (entry.pendingUserId() != null && entry.pendingUserId() != userId)))
            throw new IllegalStateException("명부가 이미 다른 계정에 연결되어 있습니다.");
        return new Applicant(school, user, email, member, entry);
    }

    private SchoolNamePlan plan(Applicant a, String name) {
        var rows = db.query("""
                SELECT id,display_name,base_name FROM school_teacher_roster WHERE school_id=? ORDER BY id
                """, (r,n) -> new SchoolNamePlan.Row(r.getLong(1),r.getString(2),r.getString(3)), a.school.id());
        var legacy = db.queryForList("""
                SELECT u.username FROM school_user m JOIN users u ON u.id=m.user_id
                WHERE m.school_id=? AND m.user_id<>? AND m.employment_status='HIRED'
                  AND NOT EXISTS(SELECT 1 FROM school_teacher_roster r WHERE r.school_user_id=m.id)
                """, String.class, a.school.id(), a.user.id());
        return SchoolNamePlan.build(a.school.id(), a.school.name(), a.user.id(), name,
                a.entry == null ? null : a.entry.id(), rows, new HashSet<>(legacy));
    }

    public SchoolNamePlan.Proposal preview(long userId, String code, String name) {
        return plan(applicant(userId, code), name).proposal;
    }

    public OnboardingResponse requestSchool(long userId, String code, String name, LocalDate hireDate,
                                             String confirmation) {
        return requestSchool(userId, code, name, hireDate, confirmation, null);
    }

    public OnboardingResponse requestSchool(long userId, String code, String name, LocalDate hireDate,
                                            String confirmation, String subjectHint) {
        Applicant a = applicant(userId, code);
        var plan = plan(a, name);
        plan.requireConfirmation(confirmation);
        var p = plan.proposal;
        if (plan.rename != null)
            db.update("UPDATE school_teacher_roster SET display_name=? WHERE id=?", p.renamedTo(), plan.rename.id());
        Member member = a.member;
        if (a.entry == null) {
            db.update("""
                    INSERT INTO school_teacher_roster(school_id,auth_email,display_name,base_name,school_user_id,pending_user_id)
                    VALUES (?,?,?,?,?,?)
                    """, a.school.id(), a.email, p.displayName(), p.baseName(),
                    member == null ? null : member.id(), member == null ? userId : null);
        } else {
            if (!plan.replay)
                db.update("UPDATE school_teacher_roster SET display_name=?,base_name=? WHERE id=?",
                        p.displayName(), p.baseName(), a.entry.id());
            if (member == null && a.entry.pendingUserId() == null) {
                // A previous application must still go through review on resubmission,
                // even at schools where an admin-created roster entry can join directly.
                int previousApplication = db.update("DELETE FROM roster_join_details WHERE roster_id=?", a.entry.id());
                if (previousApplication != 0 || a.school.id() == RosterMatchingService.SCHOOL42)
                    db.update("UPDATE school_teacher_roster SET pending_user_id=? WHERE id=?", userId, a.entry.id());
                else member = hire(a.school.id(), userId, hireDate);
            }
            if (member != null && a.entry.memberId() == null) link(a.entry.id(), member);
        }
        if (member == null) matching.recordRequest(a.school.id(), userId, subjectHint);
        selectFirstSchool(a.user, a.school, member);
        return response(userId, a.school, member);
    }

    public void reject(long adminId, long schoolId, long rosterId, java.time.Instant requestedAt) {
        lockSchool(schoolId);
        roster.requireAdmin(adminId, schoolId);
        int changed = db.update("""
                UPDATE school_teacher_roster r LEFT JOIN roster_join_details j ON j.roster_id=r.id
                SET r.pending_user_id=NULL
                WHERE r.id=? AND r.school_id=? AND r.school_user_id IS NULL AND r.pending_user_id IS NOT NULL
                  AND (j.requested_at <=> ?)
                """, rosterId, schoolId, requestedAt == null ? null : java.sql.Timestamp.from(requestedAt));
        if (changed != 1) throw new IllegalStateException("가입 신청이 변경되었습니다. 신청 목록을 새로고침해 주세요.");
        // Older pending applications may predate the request-details table.
        db.update("""
                INSERT INTO roster_join_details(roster_id) VALUES(?)
                ON DUPLICATE KEY UPDATE roster_id=VALUES(roster_id)
                """, rosterId);
    }

    public void approve(Long adminUserId, Long schoolId, Long rosterId, Long teacher) {
        School school = lockSchool(schoolId);
        roster.requireAdmin(adminUserId, schoolId);
        Entry entry = db.query("""
                SELECT id, school_user_id, pending_user_id FROM school_teacher_roster
                WHERE school_id=? AND id=? FOR UPDATE
                """, ENTRY, schoolId, rosterId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("학교 명부 항목을 찾을 수 없습니다."));
        if (entry.memberId() != null) {
            matching.bindPreviouslyApproved(schoolId, rosterId, entry.memberId(), teacher);
            return;
        }
        if (entry.pendingUserId() == null) throw new IllegalStateException("승인 대기 중인 계정이 없습니다.");
        Account user = lockAccount(entry.pendingUserId());
        Member member = member(user.id(), schoolId);
        String address = verifiedAddress(user.id());
        if (address == null || !db.queryForList(
                "SELECT id FROM school_teacher_roster WHERE id=? AND auth_email=?",
                Long.class, rosterId, address).contains(rosterId))
            throw new ForbiddenException("이메일 인증을 완료한 신청만 승인할 수 있습니다.");
        if (member != null && (!"TEACHER".equals(member.role()) || !"HIRED".equals(member.employment())))
            throw new ForbiddenException("이 계정의 학교 역할 또는 재직 상태를 확인해 주세요.");
        matching.bind(schoolId, rosterId, teacher);
        if (member == null) member = hire(schoolId, user.id(), null);
        link(entry.id(), member);
        selectFirstSchool(user, school, member);
    }

    private School lockSchoolByCode(String code) {
        if (code == null || code.isBlank()) throw new BadRequestException("학교 코드가 필요합니다.");
        return db.query("SELECT * FROM school WHERE school_code=? FOR UPDATE", SCHOOL, code.trim())
                .stream().findFirst().orElseThrow(() -> new BadRequestException("유효하지 않은 학교 코드입니다."));
    }

    private School lockSchool(long id) {
        return db.query("SELECT * FROM school WHERE id=? FOR UPDATE", SCHOOL, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));
    }

    private Account lockAccount(long id) {
        return db.query("SELECT id, active_school_id FROM users WHERE id=? FOR UPDATE",
                        (rs, n) -> new Account(rs.getLong(1), rs.getObject(2, Long.class)), id)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
    }

    private Member member(long userId, long schoolId) {
        return db.query("""
                SELECT id, position, employment_status, hire_date FROM school_user
                WHERE school_id=? AND user_id=? FOR UPDATE
                """, (rs, n) -> new Member(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDate.class)), schoolId, userId).stream().findFirst().orElse(null);
    }

    private Member hire(long schoolId, long userId, LocalDate hireDate) {
        db.update("""
                INSERT INTO school_user(school_id,user_id,position,employment_status,hire_date,created_at,updated_at)
                VALUES (?,?,'TEACHER','HIRED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, schoolId, userId, hireDate);
        return member(userId, schoolId);
    }

    private void link(long rosterId, Member member) {
        db.update("UPDATE school_teacher_roster SET school_user_id=?, pending_user_id=NULL WHERE id=?", member.id(), rosterId);
    }

    private void selectFirstSchool(Account user, School school, Member member) {
        if (user.activeSchoolId() == null && member != null && "HIRED".equals(member.employment())) {
            db.update("UPDATE users SET active_school_id=? WHERE id=? AND active_school_id IS NULL", school.id(), user.id());
        }
    }

    private String verifiedAddress(long userId) {
        return db.queryForList("SELECT auth_email FROM roster_verified_account WHERE user_id=?", String.class, userId)
                .stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public List<OnboardingResponse> pendingSchools(long userId) {
        return db.query("""
                SELECT s.* FROM school_teacher_roster r JOIN school s ON s.id=r.school_id
                WHERE r.pending_user_id=? ORDER BY s.id
                """, SCHOOL, userId).stream().map(school -> response(userId, school, null)).toList();
    }

    private OnboardingResponse response(long userId, School school, Member member) {
        return new OnboardingResponse(userId, member == null ? null : member.id(), school.id(),
                member == null ? "TEACHER" : member.role(), member == null ? "PENDING" : member.employment(),
                school.code(), school.name(), school.address(), school.phone(), member == null ? null : member.hireDate());
    }
}
