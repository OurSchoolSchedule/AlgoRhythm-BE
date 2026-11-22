# 마일스톤 1 · 시수표 파일 검증과 추가형 스키마

## 검증 API

재직 중인 활성 학교 관리자가 multipart/form-data로 `file`을 첨부한다. `sheet`는 선택할 워크시트의 정확한 이름이며 워크시트가 여러 개이면 필수다. 검증은 읽기 전용이다. 성공 결과의 ID는 학교 명부의 실제 ID다. 원본 보관·시수 확정·시간표 생성은 이 호출의 부작용이 아니다.

| 경로 | 용도 |
| --- | --- |
| POST /api/v2/terms/{termId}/load-imports/validate | 기존 학기에서 해당 학교·학년도의 명부로 검사 |
| POST /api/v2/school/load-imports/validate?academicYear=2026 | 아직 확정 시간표가 없는 학교에서 학년도별 명부로 검사 |

선택 multipart part `subjectAliases`는 application/json 객체다. 예를 들어 `{"공수1":20}`은 업로드 한 건에서 공수1을 현재 학교의 과목 ID 20으로 해석한다. 등록된 다른 과목과의 충돌 또는 타 학교 ID는 400이다. 교사 대조 키는 현재 저장소의 학교 구성원 사용자명이다. 동명이인이나 사용자명과 엑셀 이름이 다른 경우에는 자동으로 유추하지 않고 셀 오류를 반환한다.

```text
교사명  | 정식과목명 | 단축과목명 | 1학년(두 열 병합) |      | 계
        |            |            | 1반              | 2반  |
김교사  | 공통수학Ⅰ  | 공수       | 3                | 2    | 5
```

단일 머리글의 `1-1`, `1학년 2반`도 지원한다. 이름은 유니코드 NFKC·공백·대소문자 차이를 정규화한다. 과목은 정식명과 약칭 중 명부·별칭에서 하나의 ID로 결정돼야 한다. 교사·과목의 세로 병합은 좌상단 값을 사용한다. 학급 칸 공란은 0, 행의 계는 필수이며 합과 일치해야 한다. 양수의 중복 교사·과목·반 조합은 합산하지 않고 원래 셀과 중복 셀을 알린다. 합반·격주 표기, 별도 소계·다단 합계 행은 1차 양식에 포함되지 않는다. 수식은 엑셀이 저장한 계산 결과를 사용하므로 수정한 파일은 재계산·저장한다.

**성공 200 예시**

```json
{"sheet":"시수표","dataRows":1,"totalPeriods":5,"demands":[{"teacherId":"10","subjectId":"20","classId":"31","weeklyPeriods":3,"sourceCell":"D3"},{"teacherId":"10","subjectId":"20","classId":"32","weeklyPeriods":2,"sourceCell":"E3"}]}
```

**검증 실패 422 예시**

```json
{"code":"INVALID_LOAD_SHEET","message":"시수표를 수정한 후 다시 업로드하세요.","fieldErrors":[{"code":"ROW_TOTAL_MISMATCH","sheet":"시수표","row":3,"column":"F","cell":"F3","message":"학급별 합은 5, 입력한 계는 9입니다."}]}
```

행은 1부터, 열은 A부터 시작한다. 필수 머리글 오류는 해당 머리글 또는 예상 위치를 반환한다. 데이터 행 오류는 앞에서부터 최대 100건까지 반환하며 실패 시 부분 배당을 반환하지 않는다. 전체 파일 오류는 fieldErrors가 빈 배열이다.

| HTTP | 주요 코드 |
| --- | --- |
| 400 | MISSING_FILE, INVALID_PARAMETER, INVALID_YEAR, INVALID_ALIAS, ALIAS_CONFLICT |
| 403 / 404 | 기존 인가 응답. 일반 교사·휴직 관리자 403, 타 학교 학기 404 |
| 413 | WORKBOOK_LIMIT, ROW_LIMIT. 압축 파일 5 MiB, 해제 총량 50 MiB, 시트 256열·10만 셀, 데이터 5,000행. 파일 헤더 탐색은 첫 30행 |
| 415 | XLSX_REQUIRED, UNSUPPORTED_WORKBOOK. 구형 .xls, 매크로 통합문서, 외부 통합문서 참조 |
| 422 | SELECT_SHEET, UNREADABLE_XLSX 또는 INVALID_LOAD_SHEET. 셀 단위 코드에는 INVALID_HOURS, UNKNOWN_TEACHER, UNKNOWN_SUBJECT, AMBIGUOUS_TEACHER, AMBIGUOUS_SUBJECT, UNKNOWN_CLASS, CLASS_HEADER, MISSING_COLUMN_HEADER, DUPLICATE_DEMAND, MERGED_HOURS 등이 있다. |

## MySQL 8.0.16 이상 마이그레이션

대상 파일은 `src/main/resources/db/migration/V20261122_01__school_load_and_schedule_versions.sql`이다. 현재 서비스에 Flyway 자동 실행을 추가하지 않았다. 배포만으로 DB가 변경되지 않는다. 기존 앱이 사용하는 Hibernate 설정은 유지한다.

1. 대상 스키마의 백업·복구 지점과 실제 DDL을 확인한다. 준비 쿼리로 부모 PK가 signed BIGINT이며 InnoDB인지 확인한다. `timetable_set`이 이미 있다면 JPA의 id·school_id·academic_year·semester·기간 컬럼과 학기 유일키를 비교한다. 기존 사용자는 초기 SQL에서 user, JPA에서 users다. 이번 DDL은 둘 다 직접 참조하지 않는다.
2. 배포 전 복제본에서 먼저 실행한다. 새 이름이 이미 있으면 `SHOW CREATE TABLE`로 컬럼, 인덱스, FK, CHECK를 스크립트와 비교한다. MySQL DDL은 문장별 커밋되므로 중단 후에는 만들어진 테이블의 정의를 확인하고 같은 파일로 이어간다. `IF NOT EXISTS`는 기존 정의를 교정하지 않는다.
3. 기존 운영표·보결·교환의 건수와 참조 ID, 현재 구조를 기록한 뒤 선택한 DB에서 SQL 파일을 적용한다. `load_import → load_demand`, `schedule_version → weekly_lesson → dated_lesson`을 생성한다. 초기 SQL로만 구성된 DB에는 JPA 학기 테이블 `timetable_set`도 생성한다. 주간 원본·날짜별 기록을 분리하고, 날짜 변경 후에도 origin_id와 origin_date로 원수업을 추적한다.
4. 적용 후 기존 테이블의 구조·건수·참조를 비교하고 신규 테이블의 FK와 유일키를 확인한다. 이번 버전은 새 테이블에 운영 데이터를 이관하지 않으므로 마이그레이션 후 기존 쓰기·조회 경로는 동일하다. 코드 롤백 시 신규 빈 테이블을 유지할 수 있다. 운영 데이터를 삭제하는 역방향 스크립트는 포함하지 않는다.

```sql
SELECT table_name, engine FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN
('school','school_user','school_class','subject','period_setting','timetable_set','load_import','load_demand','schedule_version','weekly_lesson','dated_lesson');
SELECT table_name, column_type, column_key FROM information_schema.columns
WHERE table_schema = DATABASE() AND column_name = 'id'
AND table_name IN ('school','school_user','school_class','subject','period_setting','timetable_set');
SELECT (SELECT COUNT(*) FROM timetable) AS weekly_rows,
       (SELECT COUNT(*) FROM substitute_requests) AS cover_rows,
       (SELECT COUNT(*) FROM timetable_swap_requests) AS swap_rows;
```

신규 스키마는 M1 단일 교사·과목·일반 학급 시수 자료의 저장 기반이다. M1 API는 어떤 테이블에도 쓰지 않는다. 이후 쓰기 기능은 학기·명부의 학교 일치, 합반·공동교사의 수요 단위와 버전 전환을 별도로 구현해야 한다.
