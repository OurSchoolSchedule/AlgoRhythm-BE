-- Legacy requests retain timetable_id. Published requests bind one dated lesson revision.
-- Generate only missing alterations for installations that already ran Hibernate schema update.
SET @cover_columns = (
    SELECT GROUP_CONCAT(change_sql ORDER BY seq SEPARATOR ', ') FROM (
        SELECT 1 seq, 'MODIFY COLUMN timetable_id BIGINT NULL' change_sql
        WHERE EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
                      AND table_name='substitute_requests' AND column_name='timetable_id' AND is_nullable='NO')
        UNION ALL
        SELECT n.seq, CONCAT('ADD COLUMN ', n.name, ' ', n.typ, ' NULL') FROM (
            SELECT 2 seq,'dated_term_id' name,'BIGINT' typ UNION ALL
            SELECT 3,'dated_lesson_id','BIGINT' UNION ALL SELECT 4,'expected_lesson_rev','BIGINT' UNION ALL
            SELECT 5,'dated_period_number','INT'
        ) n WHERE NOT EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema=DATABASE()
                                AND c.table_name='substitute_requests' AND c.column_name=n.name)
    ) changes
);
SET @cover_sql = IF(@cover_columns IS NULL OR NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='substitute_requests'),'SELECT 1',CONCAT('ALTER TABLE substitute_requests ',@cover_columns));
PREPARE dated_cover_change FROM @cover_sql;
EXECUTE dated_cover_change;
DEALLOCATE PREPARE dated_cover_change;
