-- MySQL 8.0.16+. Apply after M1 and M3 in the selected application schema.
-- Account contact email is editable. Only successful OTP verification may populate this table.
CREATE TABLE IF NOT EXISTS roster_verified_account (
    user_id BIGINT NOT NULL PRIMARY KEY,
    auth_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- No existing users, school_user links or timetable records are rewritten or inferred.
CREATE TABLE IF NOT EXISTS school_teacher_roster (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    auth_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    school_user_id BIGINT NULL,
    pending_user_id BIGINT NULL,
    UNIQUE KEY uq_r1_school_email (school_id, auth_email),
    UNIQUE KEY uq_r1_member (school_user_id),
    UNIQUE KEY uq_r1_applicant (school_id, pending_user_id),
    FOREIGN KEY (school_id) REFERENCES school(id),
    FOREIGN KEY (school_user_id) REFERENCES school_user(id) ON DELETE SET NULL,
    FOREIGN KEY (pending_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS roster_term (
    roster_id BIGINT NOT NULL,
    term_id BIGINT NOT NULL,
    homeroom_class_id BIGINT NULL,
    PRIMARY KEY (roster_id, term_id),
    UNIQUE KEY uq_r1_term_homeroom (term_id, homeroom_class_id),
    FOREIGN KEY (roster_id) REFERENCES school_teacher_roster(id) ON DELETE CASCADE,
    FOREIGN KEY (term_id) REFERENCES timetable_set(id),
    FOREIGN KEY (homeroom_class_id) REFERENCES school_class(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS roster_term_subject (
    roster_id BIGINT NOT NULL,
    term_id BIGINT NOT NULL,
    subject_id BIGINT NOT NULL,
    PRIMARY KEY (roster_id, term_id, subject_id),
    FOREIGN KEY (roster_id, term_id) REFERENCES roster_term(roster_id, term_id) ON DELETE CASCADE,
    FOREIGN KEY (subject_id) REFERENCES subject(id)
) ENGINE=InnoDB;
