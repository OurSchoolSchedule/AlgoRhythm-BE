-- Whole teaching dates only. Existing terms start with no exclusions.
CREATE TABLE IF NOT EXISTS term_excluded_date (
    term_id BIGINT NOT NULL,
    excluded_date DATE NOT NULL,
    PRIMARY KEY(term_id, excluded_date),
    FOREIGN KEY(term_id) REFERENCES timetable_set(id) ON DELETE CASCADE
) ENGINE=InnoDB;
