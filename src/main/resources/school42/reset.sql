-- Only the guarded rehearsal term. Keep its catalog and staff.
DELETE FROM publication_receipt WHERE version_id IN (SELECT id FROM schedule_version WHERE term_id = 547001);

DELETE FROM dated_lesson WHERE origin_id IN (SELECT id FROM weekly_lesson WHERE version_id IN (SELECT id FROM schedule_version WHERE term_id = 547001));

DELETE FROM weekly_lesson WHERE version_id IN (SELECT id FROM schedule_version WHERE term_id = 547001);

DELETE FROM schedule_version WHERE term_id = 547001;

DELETE FROM generation_job WHERE school_id = 540001 AND term_id = 547001;
