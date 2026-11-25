package com.rssolplan.edu.domain.neis;

import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.domain.schedule.candidates.School42Round;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
class SchoolLinkStore {
    private final JdbcTemplate db;
    private final SchoolLinkReader reader;
    private final School42Round rehearsal;

    void requireSearch(long userId) {
        boolean allowed = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM users u WHERE u.id=? AND (
                  EXISTS(SELECT 1 FROM school_user m WHERE m.user_id=u.id AND m.school_id=u.active_school_id
                    AND m.position='ADMIN' AND m.employment_status='HIRED')
                  OR (u.active_school_id IS NULL AND NOT EXISTS(SELECT 1 FROM school_user m WHERE m.user_id=u.id)
                    AND NOT EXISTS(SELECT 1 FROM school_teacher_roster r WHERE r.pending_user_id=u.id))))
                """, Boolean.class, userId));
        if (!allowed) throw new ForbiddenException("학교를 검색할 권한이 없습니다.");
    }

    private void requireAdmin(long userId, long schoolId, boolean lock) {
        String sql = """
                SELECT m.id FROM users u JOIN school_user m ON m.user_id=u.id AND m.school_id=u.active_school_id
                WHERE u.id=? AND m.school_id=? AND m.position='ADMIN' AND m.employment_status='HIRED'
                """;
        if (db.queryForList(sql + (lock ? " FOR UPDATE" : ""), Long.class, userId, schoolId).isEmpty())
            throw new ForbiddenException("활성 학교의 재직 관리자만 연결할 수 있습니다.");
        if (rehearsal.isRehearsalSchool(schoolId))
            throw new ForbiddenException("실습 학교는 실제 학교와 연결할 수 없습니다.");
    }

    @Transactional(readOnly = true)
    public SchoolLink inspect(long userId, long schoolId) {
        requireAdmin(userId, schoolId, false);
        return reader.read(userId, schoolId);
    }

    // Separate bean/transaction: provider I/O has already finished before any database lock.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SchoolLink save(long userId, long schoolId, LinkChange change, SchoolOption next) {
        requireAdmin(userId, schoolId, true);
        db.queryForObject("SELECT id FROM school WHERE id=? FOR UPDATE", Long.class, schoolId);
        SchoolLink current = reader.read(userId, schoolId);
        if (current.matches(change.officeCode(), change.standardSchoolCode())) return current;
        if (current.version() != change.expectedVersion() || next == null) throw NeisSchoolException.conflict();

        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        long version = current.version() + 1;
        if (current.school() == null) {
            db.update("""
                    INSERT INTO school_neis_link(school_id,office_code,standard_school_code,office_name,school_name,
                        school_kind,address,link_version,linked_at,linked_by) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, schoolId, next.officeCode(), next.standardSchoolCode(), next.officeName(), next.schoolName(),
                    next.schoolKind(), next.address(), version, now, userId);
        } else {
            db.update("""
                    UPDATE school_neis_link SET office_code=?,standard_school_code=?,office_name=?,school_name=?,
                        school_kind=?,address=?,link_version=?,linked_at=?,linked_by=? WHERE school_id=?
                    """, next.officeCode(), next.standardSchoolCode(), next.officeName(), next.schoolName(),
                    next.schoolKind(), next.address(), version, now, userId, schoolId);
        }
        SchoolOption old = current.school();
        db.update("""
                INSERT INTO school_neis_link_audit(school_id,link_version,changed_at,changed_by,previous_office_code,
                    previous_school_code,new_office_code,new_school_code) VALUES(?,?,?,?,?,?,?,?)
                """, schoolId, version, now, userId, old == null ? null : old.officeCode(),
                old == null ? null : old.standardSchoolCode(), next.officeCode(), next.standardSchoolCode());
        return new SchoolLink(schoolId, SchoolLink.Status.LINKED, version, next, now.toInstant(ZoneOffset.UTC));
    }
}
