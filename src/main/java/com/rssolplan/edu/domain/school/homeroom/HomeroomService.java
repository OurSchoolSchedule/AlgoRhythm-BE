package com.rssolplan.edu.domain.school.homeroom;

import com.rssolplan.edu.domain.roster.TeacherRosterService;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HomeroomService {
    public record Teacher(Long schoolUserId, String displayName) {}
    public record RoomAssignment(long classId, int academicYear, int grade, int classNumber,
                                 Teacher homeroomTeacher, Teacher deputyTeacher) {}
    public record Roles(Map<Long,Long> homerooms, Map<Long,Long> deputies) {
        public boolean applied() { return !homerooms.isEmpty(); }
    }

    private final JdbcTemplate db;
    private final AuthorizationService auth;
    private final TeacherRosterService roster;

    public List<RoomAssignment> list(long user, long term) {
        long school = auth.getActiveSchoolIdOrThrow(user);
        return listFor(school, term, year(school, term, false));
    }

    @Transactional
    public List<RoomAssignment> apply(long user, long term, byte[] workbook) {
        long school = auth.getActiveSchoolIdOrThrow(user);
        // Keep the roster/active-school lock order used by the other assignment writers.
        db.queryForList("SELECT id FROM school WHERE id=? FOR UPDATE", Long.class, school);
        roster.requireAdmin(user, school);
        int year = year(school, term, true);
        Map<Long,HomeroomWorkbook.Room> rooms = new LinkedHashMap<>();
        db.query("""
                SELECT id,grade,class_number FROM school_class
                WHERE school_id=? AND academic_year=? ORDER BY grade,class_number LOCK IN SHARE MODE
                """, rs -> {
            long id = rs.getLong(1);
            rooms.put(id, new HomeroomWorkbook.Room(id, rs.getInt(2), rs.getInt(3)));
        }, school, year);
        Map<Long,String> teachers = new HashMap<>();
        db.query("""
                SELECT m.id, COALESCE(r.display_name,u.username) FROM school_user m JOIN users u ON u.id=m.user_id
                LEFT JOIN school_teacher_roster r ON r.school_user_id=m.id AND r.base_name IS NOT NULL
                WHERE m.school_id=? AND m.position='TEACHER' AND m.employment_status='HIRED'
                  AND NOT EXISTS (SELECT 1 FROM school_teacher_roster a JOIN roster_teaching_link l ON l.roster_id=a.id
                                  WHERE a.school_id=m.school_id AND a.school_user_id=m.id)
                LOCK IN SHARE MODE
                """, rs -> { teachers.put(rs.getLong(1), rs.getString(2)); }, school);
        var next = HomeroomWorkbook.parse(workbook, rooms, teachers);
        var before = rolesForTerm(term);
        boolean identical = next.size() == before.homerooms().size() && next.size() == before.deputies().size()
                && next.entrySet().stream().allMatch(e ->
                    e.getKey().equals(before.homerooms().get(e.getValue().homeroom()))
                    && e.getKey().equals(before.deputies().get(e.getValue().deputy())));
        if (!identical) {
            // Removing the previous set within this transaction permits teacher swaps between classes.
            db.update("DELETE FROM term_class_teacher_role WHERE term_id=?", term);
            db.batchUpdate("""
                    INSERT INTO term_class_teacher_role(term_id,class_id,role_name,teacher_id)
                    VALUES(?,?,'HOMEROOM',?),(?,?,'DEPUTY',?)
                    """, next.entrySet(), 100, (p, e) -> {
                p.setLong(1, term); p.setLong(2, e.getKey()); p.setLong(3, e.getValue().homeroom());
                p.setLong(4, term); p.setLong(5, e.getKey()); p.setLong(6, e.getValue().deputy());
            });
        }
        return listFor(school, term, year);
    }

    /** Called only after term and active school have been matched by the directory controller. */
    public Roles rolesForTerm(long term) {
        Map<Long,Long> primary = new HashMap<>(), deputy = new HashMap<>();
        db.query("SELECT role_name,teacher_id,class_id FROM term_class_teacher_role WHERE term_id=?", rs -> {
            (rs.getString(1).equals("HOMEROOM") ? primary : deputy).put(rs.getLong(2), rs.getLong(3));
        }, term);
        return new Roles(primary,deputy);
    }

    private int year(long school, long term, boolean lock) {
        return db.queryForList("SELECT academic_year FROM timetable_set WHERE id=? AND school_id=?" +
                        (lock ? " FOR UPDATE" : ""), Integer.class, term, school)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("현재 학교의 학기를 찾을 수 없습니다."));
    }

    /** Caller has matched the requested term and school. Keep one authority per class and teacher. */
    public List<RoomAssignment> listFor(long school, long term, int year) {
        return db.query("""
                WITH explicit_roles AS (
                    SELECT teacher_id,class_id,role_name FROM term_class_teacher_role WHERE term_id=?
                ), roster_assignments AS (
                    SELECT t.homeroom_class_id AS class_id,r.display_name,
                           coalesce(l.teacher_school_user_id,r.school_user_id) AS teacher_id
                    FROM roster_term t JOIN school_teacher_roster r ON r.id=t.roster_id
                    LEFT JOIN roster_teaching_link l ON l.roster_id=r.id
                    WHERE t.term_id=? AND r.school_id=?
                ), annual_classes AS (
                    SELECT c.id,c.grade,c.class_number,
                           coalesce(l.teacher_school_user_id,c.homeroom_teacher_id) AS annual_teacher
                    FROM school_class c LEFT JOIN school_teacher_roster r ON r.school_user_id=c.homeroom_teacher_id
                    LEFT JOIN roster_teaching_link l ON l.roster_id=r.id
                    WHERE c.school_id=? AND c.academic_year=?
                ), assignments AS (
                    SELECT c.id,c.grade,c.class_number,
                           CASE WHEN h.teacher_id IS NOT NULL THEN h.teacher_id
                                WHEN r.class_id IS NOT NULL THEN r.teacher_id
                                WHEN NOT EXISTS(SELECT 1 FROM explicit_roles f WHERE f.teacher_id=c.annual_teacher)
                                 AND NOT EXISTS(SELECT 1 FROM roster_assignments k WHERE k.teacher_id=c.annual_teacher)
                                THEN c.annual_teacher END AS primary_id,
                           CASE WHEN h.teacher_id IS NULL THEN r.display_name END AS roster_name,
                           d.teacher_id AS deputy_id
                    FROM annual_classes c
                    LEFT JOIN explicit_roles h ON h.class_id=c.id AND h.role_name='HOMEROOM'
                    LEFT JOIN explicit_roles d ON d.class_id=c.id AND d.role_name='DEPUTY'
                    LEFT JOIN roster_assignments r ON r.class_id=c.id AND NOT EXISTS(
                        SELECT 1 FROM explicit_roles f WHERE f.teacher_id=r.teacher_id)
                )
                SELECT a.id,a.grade,a.class_number,a.primary_id,coalesce(a.roster_name,pr.display_name,pu.username),
                       a.deputy_id,coalesce(dr.display_name,du.username)
                FROM assignments a
                LEFT JOIN school_user p ON p.id=a.primary_id LEFT JOIN users pu ON pu.id=p.user_id
                LEFT JOIN school_teacher_roster pr ON pr.school_user_id=p.id AND pr.base_name IS NOT NULL
                LEFT JOIN school_user d ON d.id=a.deputy_id LEFT JOIN users du ON du.id=d.user_id
                LEFT JOIN school_teacher_roster dr ON dr.school_user_id=d.id AND dr.base_name IS NOT NULL
                ORDER BY a.grade,a.class_number
                """, (rs,i) -> {
            Long primaryId = rs.getObject(4, Long.class), deputyId = rs.getObject(6, Long.class);
            return new RoomAssignment(rs.getLong(1),year,rs.getInt(2),rs.getInt(3),
                    primaryId == null && rs.getString(5) == null ? null : new Teacher(primaryId,rs.getString(5)),
                    deputyId == null ? null : new Teacher(deputyId,rs.getString(7)));
        }, term,term,school,school,year);
    }
}
