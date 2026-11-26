-- Existing rows keep their original display name until their owner confirms it.
SET @roster_name_ddl = IF(
    EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
           AND table_name='school_teacher_roster' AND column_name='base_name'),
    'SELECT 1',
    'ALTER TABLE school_teacher_roster
       ADD COLUMN base_name VARCHAR(100) NULL,
       ADD COLUMN confirmed_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
         GENERATED ALWAYS AS (IF(base_name IS NULL,NULL,display_name)) STORED,
       ADD UNIQUE KEY uq_roster_confirmed_name (school_id,confirmed_name)'
);
PREPARE roster_name_schema FROM @roster_name_ddl;
EXECUTE roster_name_schema;
DEALLOCATE PREPARE roster_name_schema;
