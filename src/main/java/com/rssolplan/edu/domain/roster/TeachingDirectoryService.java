package com.rssolplan.edu.domain.roster;

import com.rssolplan.edu.domain.school.SubjectRepository;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class TeachingDirectoryService {
    public record Subject(long id, String name) {}
    public record Teacher(Long rosterId, Long schoolUserId, String displayName,
                          Set<Long> subjectIds, Set<Integer> grades) {
        private Teacher(Long rosterId, Long schoolUserId, String displayName) {
            this(rosterId, schoolUserId, displayName, new TreeSet<>(), new TreeSet<>());
        }
    }
    public record Directory(long schoolId, long termId, Long loadId,
                            List<Subject> subjects, List<Teacher> teachers) {}

    private final AuthorizationService auth;
    private final TeacherRosterService roster;
    private final SubjectRepository subjects;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public Directory read(long userId, long termId) {
        long school = auth.getActiveSchoolIdOrThrow(userId);
        // list checks the active administrator and term before reading any directory data.
        var entries = roster.list(userId, school, termId);
        var teachers = new ArrayList<Teacher>();
        var byMembership = new HashMap<Long, Teacher>();
        for (var entry : entries) {
            var teacher = new Teacher(entry.rosterId(), entry.schoolUserId(), entry.displayName());
            entry.term().subjects().forEach(subject -> teacher.subjectIds().add(subject.subjectId()));
            teachers.add(teacher);
            if (teacher.schoolUserId() != null) byMembership.put(teacher.schoolUserId(), teacher);
        }

        // A new publication may reuse an older import with the same digest.
        Long loadId = jdbc.queryForObject("""
                SELECT (SELECT load_id FROM (
                    SELECT load_id, 1 AS published, version_no FROM schedule_version WHERE term_id=?
                    UNION ALL
                    SELECT id, 0, version_no FROM load_import WHERE term_id=?
                ) versions ORDER BY published DESC, version_no DESC LIMIT 1)
                """, Long.class, termId, termId);
        jdbc.query("""
                SELECT DISTINCT m.id, COALESCE(NULLIF(TRIM(u.username), ''), '이름 미등록') AS legacy_name,
                       a.subject_id, a.grade
                FROM school_user m JOIN users u ON u.id=m.user_id
                LEFT JOIN (
                    SELECT d.teacher_id, d.subject_id, c.grade
                    FROM load_demand d
                    JOIN school_class c ON c.id=d.class_id AND c.school_id=?
                    JOIN subject s ON s.id=d.subject_id AND s.school_id=c.school_id
                    WHERE d.load_id=?
                ) a ON a.teacher_id=m.id
                WHERE m.school_id=? AND ((m.position='TEACHER' AND m.employment_status='HIRED')
                                          OR a.subject_id IS NOT NULL)
                """, rs -> {
            long memberId = rs.getLong("id");
            var teacher = byMembership.get(memberId);
            if (teacher == null) {
                teacher = new Teacher(null, memberId, rs.getString("legacy_name"));
                teachers.add(teacher);
                byMembership.put(memberId, teacher);
            }
            Long subjectId = rs.getObject("subject_id", Long.class);
            if (subjectId != null) {
                teacher.subjectIds().add(subjectId);
                teacher.grades().add(rs.getInt("grade"));
            }
        }, school, loadId, school);
        teachers.sort(Comparator.comparing(Teacher::displayName)
                .thenComparing(Teacher::rosterId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Teacher::schoolUserId, Comparator.nullsLast(Comparator.naturalOrder())));
        var catalog = subjects.findBySchool_Id(school).stream()
                .map(s -> new Subject(s.getId(), s.getName()))
                .sorted(Comparator.comparing(Subject::name).thenComparingLong(Subject::id)).toList();
        return new Directory(school, termId, loadId, catalog, teachers);
    }
}
