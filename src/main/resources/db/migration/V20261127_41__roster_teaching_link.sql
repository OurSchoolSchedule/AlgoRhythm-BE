-- Login membership and scheduling membership have separate lifetimes.
CREATE TABLE IF NOT EXISTS roster_join_details (
    roster_id BIGINT NOT NULL PRIMARY KEY,
    subject_hint VARCHAR(100) NULL,
    requested_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (roster_id) REFERENCES school_teacher_roster(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS roster_teaching_link (
    roster_id BIGINT NOT NULL PRIMARY KEY,
    teacher_school_user_id BIGINT NOT NULL UNIQUE,
    FOREIGN KEY (roster_id) REFERENCES school_teacher_roster(id) ON DELETE CASCADE,
    FOREIGN KEY (teacher_school_user_id) REFERENCES school_user(id)
) ENGINE=InnoDB;
