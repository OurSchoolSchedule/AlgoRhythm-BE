-- Older deployments store target_type as an ENUM; preserve its existing labels and ordering.
SET @notification_target_type = (
    SELECT COLUMN_TYPE FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notifications' AND COLUMN_NAME='target_type'
);
SET @notification_target_ddl = IF(
    LEFT(@notification_target_type,5)='enum(' AND LOCATE('''LESSON_CHANGE''', @notification_target_type)=0,
    CONCAT('ALTER TABLE notifications MODIFY target_type ',
           LEFT(@notification_target_type, CHAR_LENGTH(@notification_target_type)-1),
           ',''LESSON_CHANGE'') NULL'),
    'SELECT 1'
);
PREPARE notification_target_change FROM @notification_target_ddl;
EXECUTE notification_target_change;
DEALLOCATE PREPARE notification_target_change;
