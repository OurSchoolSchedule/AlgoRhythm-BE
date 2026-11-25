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

@Service
@RequiredArgsConstructor
@Transactional
public class RosterMembershipService {
    private final JdbcTemplate db;
    private final TeacherRosterService roster;

    private record School(long id, String code, String name, String address, String phone) {}
    private record Account(long id, String name, Long activeSchoolId) {}
    private record Member(long id, String role, String employment, LocalDate hireDate) {}
    private record Entry(long id, Long memberId, Long pendingUserId) {}

    private static final RowMapper<School> SCHOOL = (rs, n) -> new School(rs.getLong("id"),
            rs.getString("school_code"), rs.getString("name"), rs.getString("address"), rs.getString("phone_number"));

    private static final RowMapper<Entry> ENTRY = (rs, n) -> new Entry(rs.getLong("id"),
            rs.getObject("school_user_id", Long.class), rs.getObject("pending_user_id", Long.class));

    /** The caller has already proved control of this mailbox by OTP in this transaction's login flow. */
    public OnboardingResponse afterVerification(long userId, String verifiedEmail, String schoolCode,
                                                String displayName, Long activeSchoolId) {
        if (schoolCode != null) {
            School school = lockSchoolByCode(schoolCode);
            return join(school, lockAccount(userId), verifiedEmail, displayName, null);
        }
        if (activeSchoolId == null) return null;
        School school = db.query("SELECT * FROM school WHERE id=? FOR UPDATE", SCHOOL, activeSchoolId)
                .stream().findFirst().orElse(null);
        if (school == null) return null;
        Account user = lockAccount(userId);
        Member member = member(userId, school.id());
        // A stale active-school pointer alone must never turn an email login into a school join.
        if (!Objects.equals(user.activeSchoolId(), activeSchoolId) || member == null) return null;
        if ("ADMIN".equals(member.role())) return response(userId, school, member);
        return join(school, user, verifiedEmail, null, null);
    }

    public OnboardingResponse requestSchool(long userId, String schoolCode, String displayName, LocalDate hireDate) {
        School school = lockSchoolByCode(schoolCode);
        Account user = lockAccount(userId);
        String email = verifiedAddress(userId);
        if (email == null) {
            Member existing = member(userId, school.id());
            if (existing != null) return response(userId, school, existing);
            throw new ForbiddenException("새 학교에 가입하려면 이메일 인증이 필요합니다.");
        }
        return join(school, user, email, displayName, hireDate);
    }

    private OnboardingResponse join(School school, Account user, String email, String displayName, LocalDate hireDate) {
        String requestedName = displayName == null ? null : TeacherRosterService.validateDisplayName(displayName);
        Member member = member(user.id(), school.id());
        Entry entry = db.query("""
                SELECT id, school_user_id, pending_user_id FROM school_teacher_roster
                WHERE school_id=? AND auth_email=? FOR UPDATE
                """, ENTRY, school.id(), email).stream().findFirst().orElse(null);
        if (entry == null) {
            // Preserve legacy employment, including ADMIN, ON_LEAVE and RESIGNED, exactly as stored.
            if (member != null && "ADMIN".equals(member.role())) return response(user.id(), school, member);
            String name = displayNameForRequest(user, requestedName, email);
            db.update("""
                    INSERT INTO school_teacher_roster(school_id,auth_email,display_name,school_user_id,pending_user_id)
                    VALUES (?,?,?,?,?)
                    """, school.id(), email, name, member == null ? null : member.id(), member == null ? user.id() : null);
        } else if (entry.memberId() != null) {
            if (member == null || entry.memberId() != member.id())
                throw new IllegalStateException("명부가 이미 다른 계정에 연결되어 있습니다.");
        } else {
            if (entry.pendingUserId() != null && entry.pendingUserId() != user.id())
                throw new IllegalStateException("다른 계정의 승인 요청이 있습니다.");
            if (member == null && entry.pendingUserId() == null) {
                member = hire(school.id(), user.id(), hireDate);
            }
            if (member != null) link(entry.id(), member);
        }
        selectFirstSchool(user, school, member);
        return response(user.id(), school, member);
    }

    private String displayNameForRequest(Account user, String requestedName, String verifiedEmail) {
        if (requestedName != null) return requestedName;
        String accountName = user.name() == null ? "" : user.name().strip();
        if (!accountName.isEmpty() && accountName.codePointCount(0, accountName.length()) <= 100) {
            return accountName;
        }
        // Old accounts can lack a usable profile name. Set only the new school display label.
        return verifiedEmail.substring(0, verifiedEmail.indexOf('@'));
    }

    public void approve(Long adminUserId, Long schoolId, Long rosterId) {
        School school = lockSchool(schoolId);
        roster.requireAdmin(adminUserId, schoolId);
        Entry entry = db.query("""
                SELECT id, school_user_id, pending_user_id FROM school_teacher_roster
                WHERE school_id=? AND id=? FOR UPDATE
                """, ENTRY, schoolId, rosterId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("학교 명부 항목을 찾을 수 없습니다."));
        if (entry.memberId() != null) return;
        if (entry.pendingUserId() == null) throw new IllegalStateException("승인 대기 중인 계정이 없습니다.");
        Account user = lockAccount(entry.pendingUserId());
        Member member = member(user.id(), schoolId);
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
        return db.query("SELECT id, username, active_school_id FROM users WHERE id=? FOR UPDATE",
                        (rs, n) -> new Account(rs.getLong(1), rs.getString(2), rs.getObject(3, Long.class)), id)
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
