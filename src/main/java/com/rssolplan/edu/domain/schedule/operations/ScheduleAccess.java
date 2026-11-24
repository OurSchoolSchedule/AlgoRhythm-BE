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
