-- Explicit per-term assignments use teaching school_user IDs (including School42's
-- original scheduling memberships), independently of signup/account ownership.
CREATE TABLE IF NOT EXISTS term_class_teacher_role (
    term_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    role_name ENUM('HOMEROOM','DEPUTY') NOT NULL,
    teacher_id BIGINT NOT NULL,
    PRIMARY KEY (term_id, class_id, role_name),
    UNIQUE KEY uq_term_class_role_teacher (term_id, teacher_id),
    FOREIGN KEY (term_id) REFERENCES timetable_set(id) ON DELETE CASCADE,
    FOREIGN KEY (class_id) REFERENCES school_class(id) ON DELETE CASCADE,
    FOREIGN KEY (teacher_id) REFERENCES school_user(id)
) ENGINE=InnoDB;
