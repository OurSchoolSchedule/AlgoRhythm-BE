package com.rssolplan.edu.domain.roster;

import com.rssolplan.edu.global.exception.BadRequestException;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeacherRosterService {
    public record IdentityInput(String authEmail, String displayName) {}
    public record AssignmentInput(List<Long> subjectIds, Long homeroomClassId) {}
    public record SubjectItem(long subjectId, String subjectName) {}
    public record Homeroom(long classId, int academicYear, int grade, int classNumber) {}
    public record Term(long termId, List<SubjectItem> subjects, Homeroom homeroomClass, boolean assignmentRecorded) {}
    public record Entry(long rosterId, long schoolId, String authEmail, String displayName, String baseName,
                        String status, Long schoolUserId, String employmentStatus, Term term, Long teachingSchoolUserId) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    private static final String LOCAL_PART = "[a-z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final String DOMAIN_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern EMAIL = Pattern.compile(
            LOCAL_PART + "(?:\\." + LOCAL_PART + ")*@" + DOMAIN_LABEL + "(?:\\." + DOMAIN_LABEL + ")+");

    public static String normalizeEmail(String value) {
        String address = value == null ? "" : value.strip();
        String email = address.toLowerCase(Locale.ROOT);
        if (address.length() > 254 || !address.chars().allMatch(ch -> ch < 128)
                || email.indexOf('@') > 64 || !EMAIL.matcher(email).matches()) {
            throw new BadRequestException("인증용 이메일 주소를 확인해 주세요.");
        }
        return email;
    }

    public static String validateDisplayName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
            throw new BadRequestException("표시 이름은 1~100자여야 합니다.");
        }
        return name;
    }

    public void requireAdmin(Long userId, Long schoolId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM school_user m JOIN users u ON u.id=m.user_id
                WHERE u.id=? AND u.active_school_id=? AND m.school_id=?
                  AND m.position='ADMIN' AND m.employment_status='HIRED'
                """, Integer.class, userId, schoolId, schoolId);
        if (count == 0) {
            throw new ForbiddenException("현재 활성 학교의 재직 관리자만 명부를 관리할 수 있습니다.");
        }
    }

    private void lockSchoolForAdmin(Long userId, Long schoolId) {
        // Every writer (including confirmed joins and approvals) takes this existing row lock before
        // looking up the roster. This also serializes two requests for an as-yet absent email.
        jdbc.queryForList("SELECT id FROM school WHERE id=? FOR UPDATE", Long.class, schoolId);
        requireAdmin(userId, schoolId);
    }

    @Transactional
    public Entry register(Long userId, Long schoolId, IdentityInput input) {
        lockSchoolForAdmin(userId, schoolId);
        String email = normalizeEmail(input.authEmail());
        String name = validateDisplayName(input.displayName());
        List<Long> existing = idsForEmail(schoolId, email);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO school_teacher_roster(school_id,auth_email,display_name) VALUES(?,?,?)",
                    schoolId, email, name);
            return one(schoolId, idsForEmail(schoolId, email).get(0), null);
        }
        Entry entry = one(schoolId, existing.get(0), null);
        if (!entry.displayName().equals(name)) {
            throw new IllegalStateException("학교 명부에 같은 이메일이 있습니다. 기존 항목을 수정해 주세요.");
        }
        return entry;
    }

    private List<Long> idsForEmail(Long schoolId, String email) {
        return jdbc.queryForList("SELECT id FROM school_teacher_roster WHERE school_id=? AND auth_email=?",
                Long.class, schoolId, email);
    }

    @Transactional
    public Entry updateIdentity(Long userId, Long schoolId, Long rosterId, IdentityInput input) {
        lockSchoolForAdmin(userId, schoolId);
        Entry old = one(schoolId, rosterId, null);
        String email = normalizeEmail(input.authEmail());
        String name = validateDisplayName(input.displayName());
        if (!old.authEmail().equals(email)) {
            if (!old.status().equals("UNLINKED")) {
                throw new IllegalStateException("가입 신청 또는 재직 연결 후에는 인증 이메일을 변경할 수 없습니다.");
            }
            if (!idsForEmail(schoolId, email).isEmpty()) {
                throw new IllegalStateException("학교 명부에 같은 이메일이 있습니다.");
            }
        }
        if (!old.displayName().equals(name) && (!old.status().equals("UNLINKED") || old.baseName() != null)) {
            checkAssignedName(schoolId, name, rosterId);
        }
        jdbc.update("""
                UPDATE school_teacher_roster SET auth_email=?,
                base_name=IF(base_name IS NOT NULL AND BINARY display_name<>BINARY ?, ?, base_name),
                display_name=? WHERE school_id=? AND id=?
                """, email, name, name, name, schoolId, rosterId);
        return one(schoolId, rosterId, null);
    }

    private void checkAssignedName(long school, String name, long own) {
        if (!jdbc.queryForList("""
                SELECT id FROM school_teacher_roster WHERE school_id=? AND BINARY display_name=BINARY ?
                AND (base_name IS NOT NULL OR school_user_id IS NOT NULL OR pending_user_id IS NOT NULL) AND id<>?
                """, Long.class, school, name, own).isEmpty())
            throw new IllegalStateException("해당 학교에서 이미 확인한 표시 이름입니다.");
    }

    public List<Entry> list(Long userId, Long schoolId, Long termId) {
        requireAdmin(userId, schoolId);
        checkTerm(schoolId, termId);
        return entries(schoolId, termId, "", List.of());
    }

    public Entry get(Long userId, Long schoolId, Long rosterId, Long termId) {
        requireAdmin(userId, schoolId);
        checkTerm(schoolId, termId);
        return one(schoolId, rosterId, termId);
    }

    public Entry self(Long userId, Long schoolId, Long termId) {
        // A pending account has no membership or active school, but may read its own request.
        List<Long> ids = jdbc.queryForList("""
                SELECT r.id FROM school_teacher_roster r
                LEFT JOIN school_user m ON m.id=r.school_user_id AND m.school_id=r.school_id
                WHERE r.school_id=? AND (r.pending_user_id=? OR (m.user_id=? AND m.employment_status='HIRED'))
                """, Long.class, schoolId, userId, userId);
        if (ids.isEmpty()) throw missingEntry();
        checkTerm(schoolId, termId);
        return one(schoolId, ids.get(0), termId);
    }

    /** Internal projection for an already authorized school directory. */
    public Map<Long, Entry> linkedByMembership(Long schoolId, Long termId) {
        checkTerm(schoolId, termId);
        Map<Long, Entry> result = new LinkedHashMap<>();
        for (Entry entry : entries(schoolId, termId, "AND r.school_user_id IS NOT NULL", List.of())) {
            result.put(entry.schoolUserId(), entry);
        }
        return result;
    }

    @Transactional
    public Entry replaceAssignment(Long userId, Long schoolId, Long rosterId, Long termId, AssignmentInput input) {
        lockSchoolForAdmin(userId, schoolId);
        one(schoolId, rosterId, null);
        Integer year = checkTerm(schoolId, termId);
        List<Long> subjects = input.subjectIds();
        if (subjects == null || subjects.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(subjects).size() != subjects.size()) {
            throw new BadRequestException("subjectIds에는 중복 없는 과목 ID 목록을 입력해 주세요.");
        }
        if (!subjects.isEmpty()) {
            var params = new MapSqlParameterSource("school", schoolId).addValue("ids", subjects);
            int found = namedJdbc.queryForObject("""
                    SELECT COUNT(*) FROM subject WHERE school_id=:school AND id IN (:ids)
                    """, params, Integer.class);
            if (found != subjects.size()) throw new NotFoundException("이 학교의 과목을 찾을 수 없습니다.");
        }
        Long homeroom = input.homeroomClassId();
        if (homeroom != null) {
            int found = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM school_class WHERE id=? AND school_id=? AND academic_year=?
                    """, Integer.class, homeroom, schoolId, year);
            if (found == 0) throw new NotFoundException("이 학교·학년도의 담임 학급을 찾을 수 없습니다.");
            int occupied = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM roster_term WHERE term_id=? AND homeroom_class_id=? AND roster_id<>?
                    """, Integer.class, termId, homeroom, rosterId);
            if (occupied != 0) throw new IllegalStateException("다른 교사가 이 학기의 담임으로 등록되어 있습니다.");
        }
        jdbc.update("""
                INSERT INTO roster_term(roster_id,term_id,homeroom_class_id) VALUES(?,?,?)
                ON DUPLICATE KEY UPDATE homeroom_class_id=?
                """, rosterId, termId, homeroom, homeroom);
        jdbc.update("DELETE FROM roster_term_subject WHERE roster_id=? AND term_id=?", rosterId, termId);
        if (!subjects.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO roster_term_subject(roster_id,term_id,subject_id) VALUES(?,?,?)",
                    subjects, 100, (statement, subjectId) -> {
                        statement.setLong(1, rosterId);
                        statement.setLong(2, termId);
                        statement.setLong(3, subjectId);
                    });
        }
        return one(schoolId, rosterId, termId);
    }

    private Integer checkTerm(Long schoolId, Long termId) {
        if (termId == null) return null;
        return jdbc.queryForList("SELECT academic_year FROM timetable_set WHERE school_id=? AND id=?",
                        Integer.class, schoolId, termId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("이 학교의 학기를 찾을 수 없습니다."));
    }

    private static NotFoundException missingEntry() {
        return new NotFoundException("해당 학교의 교사 명부 항목을 찾을 수 없습니다.");
    }

    private Entry one(Long schoolId, Long rosterId, Long termId) {
        return entries(schoolId, termId, "AND r.id=?", List.of(rosterId)).stream()
                .findFirst().orElseThrow(TeacherRosterService::missingEntry);
    }

    private List<Entry> entries(Long schoolId, Long termId, String condition, List<Object> arguments) {
        var params = new ArrayList<>();
        params.add(termId);
        params.add(schoolId);
        params.addAll(arguments);
        String sql = """
                SELECT l.teacher_school_user_id, r.id, r.auth_email, r.display_name, r.base_name, r.school_user_id, r.pending_user_id, m.employment_status,
                       t.term_id AS assignment_term_id, s.id AS subject_id, s.name AS subject_name, c.id AS class_id,
                       c.academic_year, c.grade, c.class_number
                FROM school_teacher_roster r
                LEFT JOIN school_user m ON m.id=r.school_user_id AND m.school_id=r.school_id
                LEFT JOIN roster_teaching_link l ON l.roster_id=r.id
                LEFT JOIN roster_term t ON t.roster_id=r.id AND t.term_id=?
                LEFT JOIN roster_term_subject a ON a.roster_id=t.roster_id AND a.term_id=t.term_id
                LEFT JOIN subject s ON s.id=a.subject_id
                LEFT JOIN school_class c ON c.id=t.homeroom_class_id
                WHERE r.school_id=?
                """ + condition + " ORDER BY r.id, s.id";
        return jdbc.query(sql, rs -> {
            Map<Long, Entry> byId = new LinkedHashMap<>();
            while (rs.next()) {
                long id = rs.getLong("id");
                Entry entry = byId.get(id);
                if (entry == null) {
                    entry = readEntry(rs, schoolId, termId);
                    byId.put(id, entry);
                }
                Long subject = rs.getObject("subject_id", Long.class);
                if (subject != null) {
                    entry.term().subjects().add(new SubjectItem(subject, rs.getString("subject_name")));
                }
            }
            return List.copyOf(byId.values());
        }, params.toArray());
    }

    private static Entry readEntry(ResultSet rs, long schoolId, Long termId) throws SQLException {
        Long classId = rs.getObject("class_id", Long.class);
        Homeroom homeroom = classId == null ? null : new Homeroom(classId,
                rs.getInt("academic_year"), rs.getInt("grade"), rs.getInt("class_number"));
        Term term = termId == null ? null : new Term(termId, new ArrayList<>(), homeroom,
                rs.getObject("assignment_term_id") != null);
        Long member = rs.getObject("school_user_id", Long.class);
        String status = member != null ? "LINKED" : (rs.getObject("pending_user_id") != null ? "PENDING" : "UNLINKED");
        return new Entry(rs.getLong("id"), schoolId, rs.getString("auth_email"),
                rs.getString("display_name"), rs.getString("base_name"), status, member, rs.getString("employment_status"), term, rs.getObject("teacher_school_user_id", Long.class));
    }
}
