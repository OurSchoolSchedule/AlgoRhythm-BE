package com.rssolplan.edu.domain.schedule.operations;

import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.global.exception.*;
import com.rssolplan.edu.global.security.AuthorizationService;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScheduleAccess {
    private final AuthorizationService auth;
    private final JdbcTemplate db;

    public SchoolUser member(long user, boolean admin) {
        var school = auth.getActiveSchoolIdOrThrow(user);
        var member = auth.getSchoolUserOrThrow(user, school);
        if (member.getEmploymentStatus() != SchoolUser.EmploymentStatus.HIRED
                || admin && member.getPosition() != SchoolUser.Position.ADMIN)
            throw new ForbiddenException("학교 또는 역할 권한이 없습니다.");
        return member;
    }

    public long teachingId(SchoolUser member) {
        return db.queryForList("""
                SELECT l.teacher_school_user_id FROM school_teacher_roster r
                JOIN roster_teaching_link l ON l.roster_id=r.id
                JOIN school_user t ON t.id=l.teacher_school_user_id AND t.school_id=r.school_id
                WHERE r.school_id=? AND r.school_user_id=? AND t.position='TEACHER' AND t.employment_status='HIRED'
                """, Long.class, member.getSchool().getId(), member.getId()).stream().findFirst().orElse(member.getId());
    }

    public long recipientUserId(long school, long teachingId) {
        return db.queryForList("""
                SELECT COALESCE(a.user_id,t.user_id) FROM school_user t
                LEFT JOIN roster_teaching_link l ON l.teacher_school_user_id=t.id
                LEFT JOIN school_teacher_roster r ON r.id=l.roster_id AND r.school_id=t.school_id
                LEFT JOIN school_user a ON a.id=r.school_user_id AND a.school_id=t.school_id
                    AND a.position='TEACHER' AND a.employment_status='HIRED'
                WHERE t.school_id=? AND t.id=?
                """, Long.class, school, teachingId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("학교의 수신 교사를 찾을 수 없습니다."));
    }

    public long schoolOfTerm(long user, long term, boolean admin) {
        long school = member(user, admin).getSchool().getId();
        if (db.queryForList(
                        "select id from timetable_set where id=? and school_id=?",
                        Long.class,
                        term,
                        school)
                .isEmpty()) throw new NotFoundException("학기를 찾을 수 없습니다.");
        return school;
    }

    // All schedule writers acquire this guard first. It also protects reservations on currently
    // empty slots.
    public void lockSchool(long school) {
        db.queryForObject("select id from school where id=? for update", Long.class, school);
    }
}
