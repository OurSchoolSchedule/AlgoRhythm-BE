-- Dedicated demo schema. Re-running preserves publications, approvals and sample IDs.
USE algorhythm_demo;
START TRANSACTION;
INSERT INTO school (id,name) VALUES (901,'시연고등학교')
ON DUPLICATE KEY UPDATE id=school.id;
INSERT INTO users (id,active_school_id,username,email) VALUES
(9101,901,'시연관리자','admin1@example.invalid'), (9102,901,'시연교감','admin2@example.invalid'),
(9103,901,'김수학','math@example.invalid'), (9104,901,'이영어','english@example.invalid'),
(9105,901,'박보결','cover@example.invalid')
ON DUPLICATE KEY UPDATE id=users.id;
INSERT INTO school_user (id,school_id,user_id,position,employment_status) VALUES
(9201,901,9101,'ADMIN','HIRED'), (9202,901,9102,'ADMIN','HIRED'),
(9203,901,9103,'TEACHER','HIRED'), (9204,901,9104,'TEACHER','HIRED'), (9205,901,9105,'TEACHER','HIRED')
ON DUPLICATE KEY UPDATE id=school_user.id;
INSERT INTO subject (id,school_id,name) VALUES (9301,901,'수학'), (9302,901,'영어')
ON DUPLICATE KEY UPDATE id=subject.id;
INSERT INTO school_class (id,school_id,academic_year,grade,class_number) VALUES (9401,901,2026,1,1), (9402,901,2026,1,2)
ON DUPLICATE KEY UPDATE id=school_class.id;
INSERT INTO school_setting (id,school_id,break_duration,period_duration) VALUES (9501,901,10,50)
ON DUPLICATE KEY UPDATE id=school_setting.id;
INSERT INTO period_setting (id,school_setting_id,period_number,start_time,end_time) VALUES
(9601,9501,1,'09:00','09:50'), (9602,9501,2,'10:00','10:50'), (9603,9501,3,'11:00','11:50')
ON DUPLICATE KEY UPDATE id=period_setting.id;
INSERT INTO timetable_set (id,school_id,academic_year,semester,start_date,end_date) VALUES
(9701,901,2026,2,'2026-11-23','2026-12-04')
ON DUPLICATE KEY UPDATE id=timetable_set.id;
COMMIT;
