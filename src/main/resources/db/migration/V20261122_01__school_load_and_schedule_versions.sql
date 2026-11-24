-- MySQL 8.0.16+; use the selected application schema. See sql/m1-load-import.md.
-- timetable_set exists in the current JPA model but not in the historical createDB.sql.
CREATE TABLE IF NOT EXISTS timetable_set (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    academic_year INT NOT NULL,
    semester INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    UNIQUE KEY uq_m1_term (school_id, academic_year, semester),
    FOREIGN KEY (school_id) REFERENCES school(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS load_import (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    term_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    created_by BIGINT NOT NULL,
    normalized_sha CHAR(64) NOT NULL,
    sheet_name VARCHAR(128) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_m1_load_version (term_id, version_no),
    UNIQUE KEY uq_m1_load_digest (term_id, normalized_sha),
    UNIQUE KEY uq_m1_load_term_id (term_id, id),
    FOREIGN KEY (term_id) REFERENCES timetable_set(id),
    FOREIGN KEY (created_by) REFERENCES school_user(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS load_demand (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    load_id BIGINT NOT NULL,
    teacher_id BIGINT NOT NULL,
    subject_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    weekly_periods SMALLINT UNSIGNED NOT NULL,
    source_cell VARCHAR(16) NOT NULL,
    CHECK (weekly_periods > 0),
    UNIQUE KEY uq_m1_demand (load_id, teacher_id, subject_id, class_id),
    FOREIGN KEY (load_id) REFERENCES load_import(id),
    FOREIGN KEY (teacher_id) REFERENCES school_user(id),
    FOREIGN KEY (subject_id) REFERENCES subject(id),
    FOREIGN KEY (class_id) REFERENCES school_class(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS schedule_version (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    term_id BIGINT NOT NULL,
    load_id BIGINT NULL,
    version_no INT NOT NULL,
    effective_from DATE NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_m1_schedule_version (term_id, version_no),
    FOREIGN KEY (term_id) REFERENCES timetable_set(id),
    FOREIGN KEY (term_id, load_id) REFERENCES load_import(term_id, id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS weekly_lesson (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    lesson_key VARCHAR(80) NOT NULL,
    class_id BIGINT NOT NULL,
    subject_id BIGINT NOT NULL,
    teacher_id BIGINT NOT NULL,
    weekday TINYINT UNSIGNED NOT NULL,
    period_id BIGINT NOT NULL,
    CHECK (weekday BETWEEN 1 AND 7),
    UNIQUE KEY uq_m1_weekly_key (version_id, lesson_key),
    FOREIGN KEY (version_id) REFERENCES schedule_version(id),
    FOREIGN KEY (class_id) REFERENCES school_class(id),
    FOREIGN KEY (subject_id) REFERENCES subject(id),
    FOREIGN KEY (teacher_id) REFERENCES school_user(id),
    FOREIGN KEY (period_id) REFERENCES period_setting(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS dated_lesson (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    origin_id BIGINT NOT NULL,
    origin_date DATE NOT NULL,
    teaching_date DATE NOT NULL,
    planned JSON NOT NULL,
    actual JSON NOT NULL,
    rev BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uq_m1_occurrence (origin_id, origin_date),
    KEY ix_m1_teaching_date (teaching_date),
    FOREIGN KEY (origin_id) REFERENCES weekly_lesson(id)
) ENGINE=InnoDB;
