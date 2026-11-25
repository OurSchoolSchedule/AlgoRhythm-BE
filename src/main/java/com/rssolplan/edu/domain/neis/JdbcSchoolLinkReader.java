package com.rssolplan.edu.domain.neis;

import com.rssolplan.edu.global.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Repository
@RequiredArgsConstructor
class JdbcSchoolLinkReader implements SchoolLinkReader {
    private final JdbcTemplate db;

    @Override
    public SchoolLink read(long userId, long schoolId) {
        return db.query("""
                SELECT l.* FROM users u
                JOIN school_user m ON m.user_id=u.id AND m.school_id=u.active_school_id
                LEFT JOIN school_neis_link l ON l.school_id=m.school_id
                WHERE u.id=? AND m.school_id=? AND m.employment_status='HIRED'
                  AND m.position IN ('ADMIN','TEACHER')
                """, (rs, row) -> map(rs, schoolId), userId, schoolId).stream().findFirst()
                .orElseThrow(() -> new ForbiddenException("현재 활성 학교의 재직 소속이 필요합니다."));
    }

    static SchoolLink map(ResultSet rs, long schoolId) throws SQLException {
        if (rs.getObject("link_version") == null) return SchoolLink.unlinked(schoolId);
        var school = SchoolOption.of(rs.getString("office_code"), rs.getString("standard_school_code"),
                rs.getString("office_name"), rs.getString("school_name"), rs.getString("school_kind"),
                rs.getString("address"));
        return new SchoolLink(schoolId, SchoolLink.Status.LINKED, rs.getLong("link_version"), school,
                rs.getObject("linked_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
