-- 데이터베이스 생성 및 선택
CREATE DATABASE IF NOT EXISTS school_schedule
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE school_schedule;

-- 1. 학교 정보 테이블 (기존 store)
CREATE TABLE school (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_code VARCHAR(20) NOT NULL UNIQUE COMMENT '교사/학생 등록용 학교 고유 코드',
    name VARCHAR(255) NOT NULL COMMENT '학교 이름',
    address VARCHAR(500) COMMENT '학교 주소',
    phone_number VARCHAR(20) COMMENT '학교 연락처',
    settings JSON COMMENT '학교별 설정 (학기 기간, 교시 수 등)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 학교 더미 데이터
INSERT INTO school (id, school_code, name, address, phone_number)
VALUES (1, 'SCH7781A', '신촌고등학교', '서울 서대문구 ...', '02-123-4567'),
       (2, 'SCH2211B', '역삼중학교', '서울 강남구 역삼동 ...', '02-987-6543');

-- 2. 플랫폼 사용자 테이블 (이메일 로그인 기반 - 교사 및 관리자)
CREATE TABLE user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL COMMENT '사용자 이름',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '공직자 이메일 (@korea.kr 필수)',
    password VARCHAR(255) NULL COMMENT '비밀번호 (인증번호만으로 로그인하는 OTP 방식이라면 NULL 허용)',
    profile_image_url VARCHAR(500) NULL,
    active_school_id BIGINT NULL COMMENT '현재 활성화된 학교 ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_active_school FOREIGN KEY (active_school_id) REFERENCES school(id) ON DELETE SET NULL
);

-- 사용자 더미 데이터 (이메일을 @korea.kr로 변경)
INSERT INTO user (id, username, email, active_school_id)
VALUES
(1, '교감A', 'vice_principalA@korea.kr', 1),
(2, '교감B', 'vice_principalB@korea.kr', 2),
(3, '교사1', 'teacher1@korea.kr', 1),
(4, '교사2', 'teacher2@korea.kr', 1),
(5, '교사3', 'teacher3@korea.kr', 1),
(6, '교사4', 'teacher4@korea.kr', 2),
(7, '교사5', 'teacher5@korea.kr', 2),
(8, '교사6', 'teacher6@korea.kr', 2);

-- 3. 이메일 인증번호 관리 테이블 (Spring Boot Verification 용도)
CREATE TABLE email_verification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL COMMENT '인증 요청을 한 이메일',
    auth_code VARCHAR(10) NOT NULL COMMENT '발급된 인증번호 (예: 6자리 숫자)',
    expires_at DATETIME NOT NULL COMMENT '인증번호 만료 시간 (통상 3~5분)',
    is_verified TINYINT(1) DEFAULT 0 COMMENT '인증 성공 여부 (0: 미인증, 1: 인증됨)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 조회 성능 최적화 및 만료 확인을 위한 인덱스
    INDEX idx_email_verification (email, auth_code, is_verified)
);

-- 3. 학교-교직원 관계 매핑 테이블 (기존 user_store)
CREATE TABLE school_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    position ENUM('ADMIN', 'TEACHER') NOT NULL COMMENT '학교 내 직책 (관리자/일반교사)',
    employment_status ENUM('HIRED', 'ON_LEAVE', 'RESIGNED') DEFAULT 'HIRED' COMMENT '재직 상태',
    hire_date DATE NULL COMMENT '임용/입사 날짜',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    FOREIGN KEY (school_id) REFERENCES school(id) ON DELETE CASCADE,
    UNIQUE KEY uk_school_user (user_id, school_id)
);

-- 매핑 더미 데이터
INSERT INTO school_user (id, user_id, school_id, position, employment_status, hire_date)
VALUES
(1, 1, 1, 'ADMIN', 'HIRED', '2020-03-01'), -- 신촌고 교감A
(2, 3, 1, 'TEACHER', 'HIRED', '2025-03-01'), -- 신촌고 교사1
(3, 4, 1, 'TEACHER', 'HIRED', '2025-03-01'), -- 신촌고 교사2
(4, 5, 1, 'TEACHER', 'HIRED', '2025-03-01'), -- 신촌고 교사3
(5, 2, 2, 'ADMIN', 'HIRED', '2018-03-01'), -- 역삼중 교감B
(6, 6, 2, 'TEACHER', 'HIRED', '2025-09-01'), -- 역삼중 교사4
(7, 7, 2, 'TEACHER', 'HIRED', '2025-09-01'), -- 역삼중 교사5
(8, 8, 2, 'TEACHER', 'HIRED', '2025-09-01'); -- 역삼중 교사6

-- 4. ★ 학급 테이블 (고민 해결을 위한 신설 테이블)
CREATE TABLE school_class (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL COMMENT '소속 학교',
    academic_year INT NOT NULL COMMENT '학년도 (예: 2026)',
    grade INT NOT NULL COMMENT '학년 (예: 1, 2, 3)',
    class_number INT NOT NULL COMMENT '반 (예: 1, 2, 3)',
    homeroom_teacher_id BIGINT NULL COMMENT '담임 교사 (school_user.id)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (school_id) REFERENCES school(id) ON DELETE CASCADE,
    FOREIGN KEY (homeroom_teacher_id) REFERENCES school_user(id) ON DELETE SET NULL,
    UNIQUE KEY uk_school_class_unit (school_id, academic_year, grade, class_number)
);

-- 학급 및 담임 배정 더미 데이터
INSERT INTO school_class (id, school_id, academic_year, grade, class_number, homeroom_teacher_id)
VALUES 
(1, 1, 2026, 1, 1, 2), -- 신촌고 2026학년도 1학년 1반 (담임: 교사1)
(2, 1, 2026, 1, 2, 3), -- 신촌고 2026학년도 1학년 2반 (담임: 교사2)
(3, 2, 2026, 3, 1, 6); -- 역삼중 2026학년도 3학년 1반 (담임: 교사4)

-- 5. 과목 테이블 (시간표 구성을 위한 신설 테이블)
CREATE TABLE subject (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL COMMENT '과목명 (예: 국어, 수학, 영어, 과학)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (school_id) REFERENCES school(id) ON DELETE CASCADE
);

-- 과목 더미 데이터
INSERT INTO subject (id, school_id, name) VALUES
(1, 1, '수학'), (2, 1, '영어'), (3, 1, '국어'),
(4, 2, '과학'), (5, 2, '역사');

-- 6. 학교 일과 시간 설정 (기존 store_setting)
CREATE TABLE school_setting (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL UNIQUE,
    period_duration INT NOT NULL DEFAULT 50 COMMENT '한 교시당 수업 시간(분)',
    break_duration INT NOT NULL DEFAULT 10 COMMENT '쉬는 시간(분)',
    lunch_start_time TIME COMMENT '점심시간 시작',
    lunch_end_time TIME COMMENT '점심시간 종료',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (school_id) REFERENCES school(id) ON DELETE CASCADE
);

-- 7. 교시별 타임라인 세그먼트 테이블 (기존 store_setting_segment)
CREATE TABLE period_setting (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_setting_id BIGINT NOT NULL,
    period_number INT NOT NULL COMMENT '교시 (1교시, 2교시, 3교시 등)',
    start_time TIME NOT NULL COMMENT '교시 시작 시간',
    end_time TIME NOT NULL COMMENT '교시 종료 시간',
    FOREIGN KEY (school_setting_id) REFERENCES school_setting(id) ON DELETE CASCADE,
    UNIQUE KEY uk_school_period (school_setting_id, period_number)
);

-- 8. 교시별 수업 불가능 시간 정의 (기존 work_availability)
-- (예: 파트타임 교사나 특정 요일 외부 출장으로 수업이 불가능한 타임 블록 설정)
CREATE TABLE teacher_availability (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_user_id BIGINT NOT NULL COMMENT '학교 소속 교사 고유 ID',
    day_of_week ENUM('MON', 'TUE', 'WED', 'THU', 'FRI') NOT NULL COMMENT '요일',
    period_number INT NOT NULL COMMENT '수업 불가능한 교시',
    reason VARCHAR(255) COMMENT '불가 사유',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (school_user_id) REFERENCES school_user(id) ON DELETE CASCADE,
    UNIQUE KEY uk_teacher_avail (school_user_id, day_of_week, period_number)
);

-- 9. ★ 시간표 테이블: 정규 학기 주간 단위 시간표 확정본 (기존 work_shift)
CREATE TABLE timetable (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL COMMENT '소속 학교',
    academic_year INT NOT NULL COMMENT '학년도 (예: 2026)',
    semester INT NOT NULL COMMENT '학기 (1학기: 1, 2학기: 2)',
    school_class_id BIGINT NOT NULL COMMENT '대상 학급 (학년/반)',
    period_setting_id BIGINT NOT NULL COMMENT '배정 교시',
    day_of_week ENUM('MON', 'TUE', 'WED', 'THU', 'FRI') NOT NULL COMMENT '요일',
    subject_id BIGINT NOT NULL COMMENT '수업 과목',
    teacher_id BIGINT NOT NULL COMMENT '담당 교사 (school_user.id)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (school_id) REFERENCES school(id),
    FOREIGN KEY (school_class_id) REFERENCES school_class(id),
    FOREIGN KEY (period_setting_id) REFERENCES period_setting(id),
    FOREIGN KEY (subject_id) REFERENCES subject(id),
    FOREIGN KEY (teacher_id) REFERENCES school_user(id),
    
    -- [중요 제약조건 1] 특정 학급의 특정 요일/교시에는 오직 하나의 수업만 존재 가능
    UNIQUE KEY uk_class_period_schedule (school_class_id, academic_year, semester, day_of_week, period_setting_id),
    -- [중요 제약조건 2] 특정 교사는 동일한 요일/교시에 두 개 이상의 학급에서 수업 불가 (몸이 하나이므로)
    UNIQUE KEY uk_teacher_period_schedule (teacher_id, academic_year, semester, day_of_week, period_setting_id)
);

-- 10. 교사 간 수업 교환 요청 테이블 (기존 shift_swap_requests)
-- (예: A교사의 화요일 3교시 수업과 B교사의 수요일 5교시 수업을 특정 주차에 서로 바꿈)
CREATE TABLE timetable_swap_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_id BIGINT NOT NULL,
    requester_timetable_id BIGINT NOT NULL COMMENT '요청 교사의 기존 시간표 항목',
    requester_date DATE NOT NULL COMMENT '요청 교사의 수업 변경 대상일',
    receiver_timetable_id BIGINT NOT NULL COMMENT '수신 교사의 기존 시간표 항목',
    receiver_date DATE NOT NULL COMMENT '수신 교사의 수업 변경 대상일',
    
    requester_id BIGINT NOT NULL COMMENT '요청 교사 (school_user.id)',
    receiver_id BIGINT NOT NULL COMMENT '수신 교사 (school_user.id)',
    reason TEXT NOT NULL COMMENT '교환 사유',
    status ENUM('PENDING','ACCEPTED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING' COMMENT '교사간 상호 상태',
    manager_approval_status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING' COMMENT '교감/관리자 최종 승인 상태',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_swap_school FOREIGN KEY (school_id) REFERENCES school(id) ON DELETE CASCADE,
    CONSTRAINT fk_swap_req_timetable FOREIGN KEY (requester_timetable_id) REFERENCES timetable(id) ON DELETE CASCADE,
    CONSTRAINT fk_swap_rec_timetable FOREIGN KEY (receiver_timetable_id) REFERENCES timetable(id) ON DELETE CASCADE,
    CONSTRAINT fk_swap_requester FOREIGN KEY (requester_id) REFERENCES school_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_swap_receiver FOREIGN KEY (receiver_id) REFERENCES school_user(id) ON DELETE CASCADE,

    INDEX idx_swap_status (status),
    INDEX idx_swap_mgr_status (manager_approval_status)
);

-- 11. 관리자(교감선생님 등)의 보결/대강 요청 테이블 (기존 staffing_requests)
-- (예: 특정 교사의 연가/병가로 인해 공석이 된 수업에 들어갈 대강 교사를 모집 및 지정)
CREATE TABLE substitute_requests (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  school_id           BIGINT       NOT NULL,
  owner_id            BIGINT       NOT NULL,   -- 공고를 올린 관리자 교사 (school_user.id)
  timetable_id        BIGINT       NOT NULL,   -- 보결이 필요한 원래 시간표 고유 ID
  substitute_date     DATE         NOT NULL,   -- 대강이 필요한 실제 날짜
  receiver_user_ids   VARCHAR(255) NULL,       -- 푸시 알림 대상 교사 ID 목록 (, 로 구분)
  status              ENUM('OPEN','FILLED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'OPEN' COMMENT '모집 상태',
  note                TEXT         NULL COMMENT '전달 사항 (수업 진도 등)',
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version             BIGINT       NOT NULL DEFAULT 0, -- 동시성 제어용 JPA Version

  CONSTRAINT fk_subreq_school FOREIGN KEY (school_id) REFERENCES school(id) ON DELETE RESTRICT,
  CONSTRAINT fk_subreq_owner FOREIGN KEY (owner_id) REFERENCES school_user(id) ON DELETE RESTRICT,
  CONSTRAINT fk_subreq_timetable FOREIGN KEY (timetable_id) REFERENCES timetable(id) ON DELETE RESTRICT,
  
  KEY idx_subreq_lookup (school_id, status, substitute_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 12. 대강 요청에 대한 일반 교사들의 지원/응답 테이블 (기존 staffing_responses)
CREATE TABLE substitute_responses (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  substitute_request_id BIGINT NOT NULL,
  candidate_id          BIGINT NOT NULL,   -- 지원한 교사 (school_user.id)
  worker_action         ENUM('NONE','ACCEPT','REJECT') NOT NULL DEFAULT 'NONE' COMMENT '교사의 수락 여부',
  manager_approval      ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING' COMMENT '관리자의 최종 지정 여부',
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT fk_subresp_request FOREIGN KEY (substitute_request_id) REFERENCES substitute_requests(id) ON DELETE CASCADE,
  CONSTRAINT fk_subresp_candidate FOREIGN KEY (candidate_id) REFERENCES school_user(id) ON DELETE RESTRICT,
  CONSTRAINT uq_subresp_entry UNIQUE (substitute_request_id, candidate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 13. 통합 알림 테이블 (수업 교환 및 보결 관련 알림 알림)
CREATE TABLE notifications (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL COMMENT '알림 수신 대상자 (user.id)',
  
  category     VARCHAR(16)  NULL COMMENT 'SWAP | SUBSTITUTE',
  target_type  VARCHAR(32)  NULL COMMENT 'SWAP_REQUEST | SUB_REQUEST',
  target_id    BIGINT       NULL,

  type ENUM(
    'SWAP_REQUEST_RECEIVED',
    'SWAP_NOTIFY_MANAGER',
    'SWAP_APPROVED_BY_MANAGER',
    'SWAP_REJECTED_BY_MANAGER',
    'SUBSTITUTE_REQUEST_INVITE',
    'SUBSTITUTE_NOTIFY_MANAGER',
    'SUBSTITUTE_APPROVED',
    'SUBSTITUTE_REJECTED'
  ) NOT NULL,

  message   TEXT    NOT NULL,
  is_read   BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT fk_notif_school_user FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
  KEY idx_notif_user_time (user_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 14. 교직원 복무 및 출퇴근 기록 테이블 (기존 attendance)
-- (시간표 운영 시 교사가 정시 출근했는지 여부 및 결근 체크 연동용)
CREATE TABLE teacher_attendance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    school_user_id BIGINT NOT NULL COMMENT '학교 소속 교직원 ID',
    work_date DATE NOT NULL COMMENT '근무일',
    is_checked_in TINYINT(1) DEFAULT 0 COMMENT '출근 완료 여부',
    check_in_time DATETIME NULL COMMENT '출근 시각',
    is_checked_out TINYINT(1) DEFAULT 0 COMMENT '퇴근 완료 여부',
    check_out_time DATETIME NULL COMMENT '퇴근 시각',
    status ENUM('BEFORE_WORK', 'WORKING', 'FINISHED', 'ABSENT', 'LEAVE') NOT NULL DEFAULT 'BEFORE_WORK' COMMENT '근무 상태',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (school_user_id) REFERENCES school_user(id) ON DELETE CASCADE,
    UNIQUE KEY uk_teacher_attendance_day (school_user_id, work_date)
);

-- 15. 장기 로그인 토큰 테이블
CREATE TABLE user_refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    refresh_token VARCHAR(500) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE,
    UNIQUE KEY uk_refresh_token (refresh_token)
);