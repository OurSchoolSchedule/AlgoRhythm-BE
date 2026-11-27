-- Existing rows retain their comma-separated school_user IDs.
SET @cover_recipient_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
           WHERE table_schema=DATABASE() AND table_name='substitute_requests'
             AND column_name='receiver_user_ids' AND data_type='varchar'),
    'ALTER TABLE substitute_requests MODIFY COLUMN receiver_user_ids TEXT NULL',
    'SELECT 1'
);
PREPARE cover_recipient_schema FROM @cover_recipient_ddl;
EXECUTE cover_recipient_schema;
DEALLOCATE PREPARE cover_recipient_schema;
