-- R2: normalized XLSX rows live only until confirmation/expiry; a confirmed receipt is replayable.
CREATE TABLE IF NOT EXISTS roster_import_preview (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    school_id BIGINT NOT NULL,
    term_id BIGINT NOT NULL,
    admin_id BIGINT NOT NULL,
    file_sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    state_sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    rows_json MEDIUMTEXT NULL,
    counts_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP NULL,
    KEY ix_r2_owner (school_id,term_id,admin_id,file_sha256),
    KEY ix_r2_expiry (school_id,confirmed_at,created_at),
    FOREIGN KEY (school_id) REFERENCES school(id) ON DELETE CASCADE,
    FOREIGN KEY (term_id) REFERENCES timetable_set(id) ON DELETE CASCADE,
    FOREIGN KEY (admin_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB;
