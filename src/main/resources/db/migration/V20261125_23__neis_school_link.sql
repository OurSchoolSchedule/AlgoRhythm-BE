-- UTC DATETIME(6). No backfill: absence of a row means version zero.
CREATE TABLE IF NOT EXISTS school_neis_link (
    school_id BIGINT PRIMARY KEY,
    office_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    standard_school_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    office_name VARCHAR(255),
    school_name VARCHAR(255),
    school_kind VARCHAR(100),
    address TEXT,
    link_version BIGINT NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    linked_by BIGINT NOT NULL,
    KEY ix_neis_external (office_code, standard_school_code),
    CHECK (CHAR_LENGTH(TRIM(office_code)) > 0 AND CHAR_LENGTH(TRIM(standard_school_code)) > 0),
    CHECK (link_version > 0),
    FOREIGN KEY (school_id) REFERENCES school(id),
    FOREIGN KEY (linked_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS school_neis_link_audit (
    school_id BIGINT NOT NULL,
    link_version BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    changed_by BIGINT NOT NULL,
    previous_office_code VARCHAR(16),
    previous_school_code VARCHAR(16),
    new_office_code VARCHAR(16) NOT NULL,
    new_school_code VARCHAR(16) NOT NULL,
    PRIMARY KEY (school_id, link_version),
    FOREIGN KEY (school_id) REFERENCES school(id),
    FOREIGN KEY (changed_by) REFERENCES users(id)
) ENGINE=InnoDB;
