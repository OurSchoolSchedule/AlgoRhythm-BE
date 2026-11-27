package com.rssolplan.edu.domain.roster;

import com.rssolplan.edu.global.exception.BadRequestException;
import com.rssolplan.edu.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RosterMatchingService {
    // Only IDs and identities owned by the School42 fixture can be claimed.
    static final long SCHOOL42 = 540001;
    private static final String ELIGIBLE = """
            FROM school_user m JOIN users u ON u.id=m.user_id
            WHERE m.school_id=? AND m.school_id=540001 AND m.id BETWEEN 542001 AND 542042
            AND m.user_id=m.id-1000 AND u.provider IS NULL AND u.provider_id IS NULL
            AND u.email=CONCAT('teacher42-',LPAD(m.id-542000,2,'0'),'@example.invalid')
            AND m.position='TEACHER' AND m.employment_status='HIRED'
            AND NOT EXISTS (SELECT 1 FROM roster_verified_account v WHERE v.user_id=u.id)
            AND NOT EXISTS (SELECT 1 FROM school_teacher_roster r WHERE r.school_user_id=m.id)
            """;

    public record Candidate(long teacherSchoolUserId, String displayName, List<String> subjects,
                            int weeklyPeriods, boolean linked) {}
    public record Options(String applicantName, String verifiedEmail, String subjectHint, Instant requestedAt,
                          Long termId, boolean requiresSelection, List<Candidate> candidates) {}
    private record Applicant(String name, String email, String hint, Instant time) {}
    private record SubjectLoad(String name, int hours) {}

    private final JdbcTemplate db;
    private final TeacherRosterService roster;

    @Transactional(readOnly = true)
    public Options options(long admin, long school, long rosterId, Long termId) {
        roster.requireAdmin(admin, school);
        Applicant applicant = db.query("""
                SELECT COALESCE(r.base_name,r.display_name),v.auth_email,j.subject_hint,j.requested_at
                FROM school_teacher_roster r
                LEFT JOIN school_user a ON a.id=r.school_user_id AND a.school_id=r.school_id
                LEFT JOIN roster_verified_account v ON v.user_id=COALESCE(r.pending_user_id,a.user_id) AND v.auth_email=r.auth_email
                LEFT JOIN roster_join_details j ON j.roster_id=r.id
                WHERE r.school_id=? AND r.id=? AND ((r.pending_user_id IS NOT NULL AND r.school_user_id IS NULL)
                  OR (r.school_id=540001 AND a.position='TEACHER' AND a.employment_status='HIRED'
                      AND NOT EXISTS (SELECT 1 FROM roster_teaching_link l WHERE l.roster_id=r.id)))
                """, (rs, n) -> new Applicant(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()), school, rosterId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("승인 대기 신청을 찾을 수 없습니다."));
        if (termId == null) {
            termId = db.queryForList("SELECT id FROM timetable_set WHERE school_id=? ORDER BY academic_year DESC,semester DESC LIMIT 1",
                    Long.class, school).stream().findFirst().orElse(null);
        } else if (db.queryForList("SELECT id FROM timetable_set WHERE school_id=? AND id=?", Long.class, school, termId).isEmpty()) {
            throw new NotFoundException("현재 학교의 학기를 찾을 수 없습니다.");
        }
        return new Options(applicant.name, applicant.email, applicant.hint, applicant.time, termId, school == SCHOOL42,
                school == SCHOOL42 ? candidates(school, termId) : List.of());
    }

    private List<Candidate> candidates(long school, Long termId) {
        // A publication can intentionally reuse a load older than the last uploaded file.
        Long load = db.queryForObject("""
                SELECT (SELECT load_id FROM (
                  SELECT load_id,1 published,version_no FROM schedule_version WHERE term_id=? AND load_id IS NOT NULL
                  UNION ALL SELECT id,0,version_no FROM load_import WHERE term_id=?
                ) q ORDER BY published DESC,version_no DESC LIMIT 1)
                """, Long.class, termId, termId);
        Map<Long, List<SubjectLoad>> loads = new LinkedHashMap<>();
        db.query("""
                SELECT d.teacher_id,s.name,SUM(d.weekly_periods) FROM load_demand d
                JOIN subject s ON s.id=d.subject_id AND s.school_id=? WHERE d.load_id=?
                GROUP BY d.teacher_id,s.id,s.name ORDER BY s.name,s.id
                """, rs -> { loads.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                    .add(new SubjectLoad(rs.getString(2), rs.getInt(3))); }, school, load);
        return db.query("""
                SELECT m.id,u.username,EXISTS(SELECT 1 FROM roster_teaching_link l WHERE l.teacher_school_user_id=m.id)
                """ + ELIGIBLE + " ORDER BY u.username,m.id", (rs, n) -> {
            var subjects = loads.getOrDefault(rs.getLong(1), List.of());
            return new Candidate(rs.getLong(1),rs.getString(2),subjects.stream().map(SubjectLoad::name).toList(),
                    subjects.stream().mapToInt(SubjectLoad::hours).sum(),rs.getBoolean(3));
        }, school);
    }

    /** Caller holds the school lock; both the approval and the link are committed together. */
    void bind(long school, long rosterId, Long teacher) {
        if (teacher == null) {
            if (school == SCHOOL42) throw new BadRequestException("승인할 기존 시연 교사를 선택해 주세요.");
            return;
        }
        if (school != SCHOOL42 || db.queryForList("SELECT m.id " + ELIGIBLE + " AND m.id=? FOR UPDATE",
                Long.class, school, teacher).isEmpty())
            throw new BadRequestException("이 학교에서 연결할 수 있는 기존 시연 교사를 선택해 주세요.");
        if (!db.queryForList("SELECT roster_id FROM roster_teaching_link WHERE teacher_school_user_id=? FOR UPDATE",
                Long.class, teacher).isEmpty())
            throw new IllegalStateException("선택한 교사는 이미 다른 계정에 연결되어 있습니다.");
        db.update("INSERT INTO roster_teaching_link(roster_id,teacher_school_user_id) VALUES(?,?)", rosterId, teacher);
    }

    void recordRequest(long school, long user, String subjectHint) {
        String hint = subjectHint == null || subjectHint.isBlank() ? null : subjectHint.strip();
        if (hint != null && hint.codePointCount(0,hint.length()) > 100)
            throw new BadRequestException("담당 과목은 100자 이내로 입력해 주세요.");
        db.update("""
                INSERT INTO roster_join_details(roster_id,subject_hint)
                SELECT id,? FROM school_teacher_roster WHERE school_id=? AND pending_user_id=?
                ON DUPLICATE KEY UPDATE subject_hint=COALESCE(VALUES(subject_hint),subject_hint)
                """, hint, school, user);
    }

    void bindPreviouslyApproved(long school, long rosterId, long accountMember, Long teacher) {
        if (teacher == null) return;
        var previous = db.queryForList("SELECT teacher_school_user_id FROM roster_teaching_link WHERE roster_id=?",
                Long.class, rosterId);
        if (previous.contains(teacher)) return;
        if (!previous.isEmpty()) throw new IllegalStateException("이미 다른 교사로 승인된 신청입니다.");
        Integer eligible = db.queryForObject("""
                SELECT COUNT(*) FROM school_user a
                JOIN school_teacher_roster r ON r.school_user_id=a.id AND r.school_id=a.school_id
                JOIN roster_verified_account v ON v.user_id=a.user_id AND v.auth_email=r.auth_email
                WHERE r.id=? AND a.id=? AND a.school_id=? AND a.position='TEACHER' AND a.employment_status='HIRED'
                AND NOT EXISTS (SELECT 1 FROM weekly_lesson WHERE teacher_id=a.id)
                AND NOT EXISTS (SELECT 1 FROM timetable WHERE teacher_id=a.id)
                AND NOT EXISTS (SELECT 1 FROM load_demand WHERE teacher_id=a.id)
                AND NOT EXISTS (SELECT 1 FROM school_class WHERE homeroom_teacher_id=a.id)
                AND NOT EXISTS (SELECT 1 FROM teacher_availability WHERE school_user_id=a.id)
                AND NOT EXISTS (SELECT 1 FROM lesson_change WHERE requester=a.id OR responder=a.id)
                AND NOT EXISTS (SELECT 1 FROM roster_term WHERE roster_id=r.id)
                """, Integer.class, rosterId, accountMember, school);
        if (eligible != 1) throw new IllegalStateException("기존 소속의 수업 배정, 인증 이메일 및 재직 상태를 확인해 주세요.");
        bind(school, rosterId, teacher);
    }

    public record LinkedAccount(long memberId, String displayName) {}

    public Map<Long, Long> directoryLinks(long school) {
        Map<Long, Long> result = new LinkedHashMap<>();
        linkedAccounts(school).forEach((teacher, account) -> {
            if (teacher != account.memberId()) result.put(teacher, account.memberId());
        });
        return result;
    }

    public Map<Long, LinkedAccount> linkedAccounts(long school) {
        Map<Long, LinkedAccount> links = new LinkedHashMap<>();
        db.query("""
                SELECT COALESCE(t.id,a.id), a.id, r.display_name
                FROM school_teacher_roster r
                JOIN school_user a ON a.id=r.school_user_id AND a.school_id=r.school_id
                LEFT JOIN roster_teaching_link l ON l.roster_id=r.id AND r.school_id=?
                LEFT JOIN school_user t ON t.id=l.teacher_school_user_id AND t.school_id=r.school_id
                    AND t.position='TEACHER' AND t.employment_status='HIRED'
                WHERE r.school_id=? AND a.position='TEACHER' AND a.employment_status='HIRED'
                    AND (l.roster_id IS NULL OR t.id IS NOT NULL)
                """, rs -> { links.put(rs.getLong(1), new LinkedAccount(rs.getLong(2), rs.getString(3))); }, SCHOOL42, school);
        return links;
    }

}
