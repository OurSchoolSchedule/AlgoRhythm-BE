-- Apply after V20261122_01. All tables use the existing signed BIGINT identifiers.
CREATE TABLE IF NOT EXISTS generation_job (
 id CHAR(36) PRIMARY KEY, school_id BIGINT NOT NULL, term_id BIGINT NULL, academic_year INT NOT NULL,
 base_rev BIGINT NOT NULL, snapshot JSON NOT NULL, state VARCHAR(32) NOT NULL,
 created_at DATETIME(6) NOT NULL, deadline DATETIME(6) NOT NULL, run_token CHAR(36), lease_until DATETIME(6),
 result JSON, INDEX ix_generation_claim(state,lease_until,deadline),
 FOREIGN KEY (school_id) REFERENCES school(id), FOREIGN KEY (term_id) REFERENCES timetable_set(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS publication_receipt (
 job_id CHAR(36) PRIMARY KEY, candidate_id CHAR(64) NOT NULL, version_id BIGINT NOT NULL UNIQUE,
 FOREIGN KEY (job_id) REFERENCES generation_job(id), FOREIGN KEY (version_id) REFERENCES schedule_version(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS lesson_change (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, term_id BIGINT NOT NULL, kind VARCHAR(12) NOT NULL,
 requester BIGINT NOT NULL, responder BIGINT NOT NULL, first_id BIGINT NOT NULL, first_rev BIGINT NOT NULL,
 second_id BIGINT, second_rev BIGINT, state VARCHAR(16) NOT NULL, rev BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY (term_id) REFERENCES timetable_set(id), FOREIGN KEY (requester) REFERENCES school_user(id),
 FOREIGN KEY (responder) REFERENCES school_user(id), FOREIGN KEY (first_id) REFERENCES dated_lesson(id),
 FOREIGN KEY (second_id) REFERENCES dated_lesson(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS lesson_approval (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, school_id BIGINT NOT NULL, actor BIGINT NOT NULL,
 key_id CHAR(36) NOT NULL, request_ref VARCHAR(96) NOT NULL, response JSON NOT NULL, changes_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_approval_key(school_id,actor,key_id),
 FOREIGN KEY (school_id) REFERENCES school(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS legacy_lesson_rev (
 timetable_id BIGINT NOT NULL, lesson_date DATE NOT NULL, rev BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(timetable_id,lesson_date)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS legacy_approval_input (
 kind VARCHAR(12) NOT NULL, request_id BIGINT NOT NULL, side_no INT NOT NULL,
 dated_id BIGINT, expected_rev BIGINT NOT NULL, PRIMARY KEY(kind,request_id,side_no),
 FOREIGN KEY (dated_id) REFERENCES dated_lesson(id)
) ENGINE=InnoDB;
