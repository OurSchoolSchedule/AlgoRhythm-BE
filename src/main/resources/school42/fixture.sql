-- Synthetic School42 rows. The caller owns the transaction and checks for ID collisions.
INSERT INTO school (id, name)
SELECT v.id, v.name
FROM (
  SELECT 540001 AS id, '시연한들고등학교' AS name
) v
LEFT JOIN school x ON x.id = v.id AND x.name = v.name
WHERE x.id IS NULL;

INSERT INTO users (id, active_school_id, username, email)
SELECT v.id, v.active_school_id, v.username, v.email
FROM (
  SELECT 541000 AS id, 540001 AS active_school_id, '한들시연관리자' AS username, 'admin42@example.invalid' AS email
  UNION ALL SELECT 541001, 540001, '김서윤', 'teacher42-01@example.invalid'
  UNION ALL SELECT 541002, 540001, '이지후', 'teacher42-02@example.invalid'
  UNION ALL SELECT 541003, 540001, '박민서', 'teacher42-03@example.invalid'
  UNION ALL SELECT 541004, 540001, '최도현', 'teacher42-04@example.invalid'
  UNION ALL SELECT 541005, 540001, '정수빈', 'teacher42-05@example.invalid'
  UNION ALL SELECT 541006, 540001, '강태영', 'teacher42-06@example.invalid'
  UNION ALL SELECT 541007, 540001, '조예원', 'teacher42-07@example.invalid'
  UNION ALL SELECT 541008, 540001, '윤지민', 'teacher42-08@example.invalid'
  UNION ALL SELECT 541009, 540001, '장서준', 'teacher42-09@example.invalid'
  UNION ALL SELECT 541010, 540001, '임주원', 'teacher42-10@example.invalid'
  UNION ALL SELECT 541011, 540001, '한다은', 'teacher42-11@example.invalid'
  UNION ALL SELECT 541012, 540001, '오현우', 'teacher42-12@example.invalid'
  UNION ALL SELECT 541013, 540001, '서연수', 'teacher42-13@example.invalid'
  UNION ALL SELECT 541014, 540001, '신승현', 'teacher42-14@example.invalid'
  UNION ALL SELECT 541015, 540001, '권채원', 'teacher42-15@example.invalid'
  UNION ALL SELECT 541016, 540001, '황하준', 'teacher42-16@example.invalid'
  UNION ALL SELECT 541017, 540001, '안다인', 'teacher42-17@example.invalid'
  UNION ALL SELECT 541018, 540001, '송민재', 'teacher42-18@example.invalid'
  UNION ALL SELECT 541019, 540001, '전유림', 'teacher42-19@example.invalid'
  UNION ALL SELECT 541020, 540001, '홍건우', 'teacher42-20@example.invalid'
  UNION ALL SELECT 541021, 540001, '김서연', 'teacher42-21@example.invalid'
  UNION ALL SELECT 541022, 540001, '이도윤', 'teacher42-22@example.invalid'
  UNION ALL SELECT 541023, 540001, '박나경', 'teacher42-23@example.invalid'
  UNION ALL SELECT 541024, 540001, '최우진', 'teacher42-24@example.invalid'
  UNION ALL SELECT 541025, 540001, '정소율', 'teacher42-25@example.invalid'
  UNION ALL SELECT 541026, 540001, '강재윤', 'teacher42-26@example.invalid'
  UNION ALL SELECT 541027, 540001, '조시현', 'teacher42-27@example.invalid'
  UNION ALL SELECT 541028, 540001, '윤윤서', 'teacher42-28@example.invalid'
  UNION ALL SELECT 541029, 540001, '장지완', 'teacher42-29@example.invalid'
  UNION ALL SELECT 541030, 540001, '임은채', 'teacher42-30@example.invalid'
  UNION ALL SELECT 541031, 540001, '한지안', 'teacher42-31@example.invalid'
  UNION ALL SELECT 541032, 540001, '오준영', 'teacher42-32@example.invalid'
  UNION ALL SELECT 541033, 540001, '서가은', 'teacher42-33@example.invalid'
  UNION ALL SELECT 541034, 540001, '신지호', 'teacher42-34@example.invalid'
  UNION ALL SELECT 541035, 540001, '권보민', 'teacher42-35@example.invalid'
  UNION ALL SELECT 541036, 540001, '황하람', 'teacher42-36@example.invalid'
  UNION ALL SELECT 541037, 540001, '안이수', 'teacher42-37@example.invalid'
  UNION ALL SELECT 541038, 540001, '송지유', 'teacher42-38@example.invalid'
  UNION ALL SELECT 541039, 540001, '전서진', 'teacher42-39@example.invalid'
  UNION ALL SELECT 541040, 540001, '홍도원', 'teacher42-40@example.invalid'
  UNION ALL SELECT 541041, 540001, '김소은', 'teacher42-41@example.invalid'
  UNION ALL SELECT 541042, 540001, '이현준', 'teacher42-42@example.invalid'
) v
LEFT JOIN users x ON x.id = v.id AND x.active_school_id = v.active_school_id AND x.username = v.username AND x.email = v.email
WHERE x.id IS NULL;

INSERT INTO school_user (id, school_id, user_id, position, employment_status)
SELECT v.id, v.school_id, v.user_id, v.position, v.employment_status
FROM (
  SELECT 542000 AS id, 540001 AS school_id, 541000 AS user_id, 'ADMIN' AS position, 'HIRED' AS employment_status
  UNION ALL SELECT 542001, 540001, 541001, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542002, 540001, 541002, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542003, 540001, 541003, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542004, 540001, 541004, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542005, 540001, 541005, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542006, 540001, 541006, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542007, 540001, 541007, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542008, 540001, 541008, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542009, 540001, 541009, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542010, 540001, 541010, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542011, 540001, 541011, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542012, 540001, 541012, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542013, 540001, 541013, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542014, 540001, 541014, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542015, 540001, 541015, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542016, 540001, 541016, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542017, 540001, 541017, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542018, 540001, 541018, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542019, 540001, 541019, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542020, 540001, 541020, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542021, 540001, 541021, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542022, 540001, 541022, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542023, 540001, 541023, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542024, 540001, 541024, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542025, 540001, 541025, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542026, 540001, 541026, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542027, 540001, 541027, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542028, 540001, 541028, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542029, 540001, 541029, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542030, 540001, 541030, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542031, 540001, 541031, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542032, 540001, 541032, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542033, 540001, 541033, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542034, 540001, 541034, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542035, 540001, 541035, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542036, 540001, 541036, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542037, 540001, 541037, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542038, 540001, 541038, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542039, 540001, 541039, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542040, 540001, 541040, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542041, 540001, 541041, 'TEACHER', 'HIRED'
  UNION ALL SELECT 542042, 540001, 541042, 'TEACHER', 'HIRED'
) v
LEFT JOIN school_user x ON x.id = v.id AND x.school_id = v.school_id AND x.user_id = v.user_id AND x.position = v.position AND x.employment_status = v.employment_status
WHERE x.id IS NULL;

INSERT INTO subject (id, school_id, name)
SELECT v.id, v.school_id, v.name
FROM (
  SELECT 543001 AS id, 540001 AS school_id, '공통국어2' AS name
  UNION ALL SELECT 543002, 540001, '공통수학2'
  UNION ALL SELECT 543003, 540001, '공통영어2'
  UNION ALL SELECT 543004, 540001, '통합사회2'
  UNION ALL SELECT 543005, 540001, '통합과학2'
  UNION ALL SELECT 543006, 540001, '과학탐구실험2'
  UNION ALL SELECT 543007, 540001, '한국사2'
  UNION ALL SELECT 543008, 540001, '체육2'
  UNION ALL SELECT 543009, 540001, '정보'
  UNION ALL SELECT 543010, 540001, '기술·가정'
  UNION ALL SELECT 543011, 540001, '음악'
  UNION ALL SELECT 543012, 540001, '미술'
  UNION ALL SELECT 543013, 540001, '독서와 작문'
  UNION ALL SELECT 543014, 540001, '미적분Ⅰ'
  UNION ALL SELECT 543015, 540001, '영어Ⅱ'
  UNION ALL SELECT 543016, 540001, '사회와 문화'
  UNION ALL SELECT 543017, 540001, '화학'
  UNION ALL SELECT 543018, 540001, '세계사'
  UNION ALL SELECT 543019, 540001, '스포츠 생활1'
  UNION ALL SELECT 543020, 540001, '인공지능 기초'
  UNION ALL SELECT 543021, 540001, '생활과학 탐구'
  UNION ALL SELECT 543022, 540001, '진로와 직업'
  UNION ALL SELECT 543023, 540001, '미술 창작'
  UNION ALL SELECT 543024, 540001, '음악 연주와 창작'
  UNION ALL SELECT 543025, 540001, '언어와 매체'
  UNION ALL SELECT 543026, 540001, '확률과 통계'
  UNION ALL SELECT 543027, 540001, '영어 독해와 작문'
  UNION ALL SELECT 543028, 540001, '사회·문화'
  UNION ALL SELECT 543029, 540001, '생활과 과학'
  UNION ALL SELECT 543030, 540001, '동아시아사'
  UNION ALL SELECT 543031, 540001, '스포츠 생활'
  UNION ALL SELECT 543032, 540001, '공학 일반'
  UNION ALL SELECT 543033, 540001, '음악 감상과 비평'
  UNION ALL SELECT 543034, 540001, '미술 감상과 비평'
  UNION ALL SELECT 543035, 540001, '창의적 체험활동'
) v
LEFT JOIN subject x ON x.id = v.id AND x.school_id = v.school_id AND x.name = v.name
WHERE x.id IS NULL;

INSERT INTO school_class (id, school_id, academic_year, grade, class_number)
SELECT v.id, v.school_id, v.academic_year, v.grade, v.class_number
FROM (
  SELECT 544001 AS id, 540001 AS school_id, 2026 AS academic_year, 1 AS grade, 1 AS class_number
  UNION ALL SELECT 544002, 540001, 2026, 1, 2
  UNION ALL SELECT 544003, 540001, 2026, 1, 3
  UNION ALL SELECT 544004, 540001, 2026, 1, 4
  UNION ALL SELECT 544005, 540001, 2026, 1, 5
  UNION ALL SELECT 544006, 540001, 2026, 1, 6
  UNION ALL SELECT 544007, 540001, 2026, 1, 7
  UNION ALL SELECT 544008, 540001, 2026, 2, 1
  UNION ALL SELECT 544009, 540001, 2026, 2, 2
  UNION ALL SELECT 544010, 540001, 2026, 2, 3
  UNION ALL SELECT 544011, 540001, 2026, 2, 4
  UNION ALL SELECT 544012, 540001, 2026, 2, 5
  UNION ALL SELECT 544013, 540001, 2026, 2, 6
  UNION ALL SELECT 544014, 540001, 2026, 2, 7
  UNION ALL SELECT 544015, 540001, 2026, 3, 1
  UNION ALL SELECT 544016, 540001, 2026, 3, 2
  UNION ALL SELECT 544017, 540001, 2026, 3, 3
  UNION ALL SELECT 544018, 540001, 2026, 3, 4
  UNION ALL SELECT 544019, 540001, 2026, 3, 5
  UNION ALL SELECT 544020, 540001, 2026, 3, 6
  UNION ALL SELECT 544021, 540001, 2026, 3, 7
) v
LEFT JOIN school_class x ON x.id = v.id AND x.school_id = v.school_id AND x.academic_year = v.academic_year AND x.grade = v.grade AND x.class_number = v.class_number
WHERE x.id IS NULL;

INSERT INTO school_setting (id, school_id, break_duration, period_duration)
SELECT v.id, v.school_id, v.break_duration, v.period_duration
FROM (
  SELECT 545001 AS id, 540001 AS school_id, 10 AS break_duration, 50 AS period_duration
) v
LEFT JOIN school_setting x ON x.id = v.id AND x.school_id = v.school_id AND x.break_duration = v.break_duration AND x.period_duration = v.period_duration
WHERE x.id IS NULL;

INSERT INTO period_setting (id, school_setting_id, period_number, start_time, end_time)
SELECT v.id, v.school_setting_id, v.period_number, v.start_time, v.end_time
FROM (
  SELECT 546001 AS id, 545001 AS school_setting_id, 1 AS period_number, '08:40:00' AS start_time, '09:30:00' AS end_time
  UNION ALL SELECT 546002, 545001, 2, '09:40:00', '10:30:00'
  UNION ALL SELECT 546003, 545001, 3, '10:40:00', '11:30:00'
  UNION ALL SELECT 546004, 545001, 4, '11:40:00', '12:30:00'
  UNION ALL SELECT 546005, 545001, 5, '13:30:00', '14:20:00'
  UNION ALL SELECT 546006, 545001, 6, '14:30:00', '15:20:00'
  UNION ALL SELECT 546007, 545001, 7, '15:30:00', '16:20:00'
) v
LEFT JOIN period_setting x ON x.id = v.id AND x.school_setting_id = v.school_setting_id AND x.period_number = v.period_number AND x.start_time = v.start_time AND x.end_time = v.end_time
WHERE x.id IS NULL;

INSERT INTO timetable_set (id, school_id, academic_year, semester, start_date, end_date)
SELECT v.id, v.school_id, v.academic_year, v.semester, v.start_date, v.end_date
FROM (
  SELECT 547001 AS id, 540001 AS school_id, 2026 AS academic_year, 2 AS semester, '2026-11-23' AS start_date, '2026-12-04' AS end_date
) v
LEFT JOIN timetable_set x ON x.id = v.id AND x.school_id = v.school_id AND x.academic_year = v.academic_year AND x.semester = v.semester AND x.start_date = v.start_date AND x.end_date = v.end_date
WHERE x.id IS NULL;

INSERT INTO teacher_availability (id, school_user_id, day_of_week, period_number)
SELECT v.id, v.school_user_id, v.day_of_week, v.period_number
FROM (
  SELECT 548001 AS id, 542001 AS school_user_id, 'MON' AS day_of_week, 1 AS period_number
  UNION ALL SELECT 548002, 542007, 'TUE', 4
  UNION ALL SELECT 548003, 542013, 'WED', 5
  UNION ALL SELECT 548004, 542019, 'THU', 2
  UNION ALL SELECT 548005, 542023, 'FRI', 3
  UNION ALL SELECT 548006, 542028, 'MON', 6
) v
LEFT JOIN teacher_availability x ON x.id = v.id AND x.school_user_id = v.school_user_id AND x.day_of_week = v.day_of_week AND x.period_number = v.period_number
WHERE x.id IS NULL;
