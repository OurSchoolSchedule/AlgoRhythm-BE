# BE-R2 · 학교 교사 명부 일괄 등록

FE18의 작업 순서는 학교·학기 선택 → 양식 다운로드 → 작성한 파일의 미리보기 → 관리자 확정이다. 모든 API에 `Authorization: Bearer <accessToken>`을 보낸다. 서버는 매번 현재 활성 학교, 해당 학교의 `HIRED` 재직과 `ADMIN` 역할을 확인한다. 시수표 업로드와는 별도의 API다.

## 양식과 코드

| 항목 | 규칙 |
| --- | --- |
| 파일 | .xlsx의 `교사명부` 시트. 1행 머리글은 아래 표와 같고, 2행부터 입력한다. 다운로드한 파일의 `코드표` 시트에서 학교 과목과 해당 학년도 학급의 코드를 복사한다. 서버는 DB의 코드표로 검증한다. |
| 인증 이메일 | 학교 내 교사 식별 키. BE-R1과 같이 앞뒤 공백 제거·소문자 정규화. 같은 이름을 가진 교사도 이메일이 다르면 다른 항목이다. |
| 표시 이름 | 같은 이메일의 모든 행에 같은 이름을 입력한다. 1~100자. 기존 계정의 프로필 이름은 보존한다. |
| 과목 코드 | 한 행에 한 과목. 이 버전은 학교의 `subject.id`를 코드로 사용한다. 여러 과목이면 이메일·이름을 반복한다. 미배정은 과목 코드를 비운 한 행으로 입력한다. |
| 담임 학급 코드 | 선택 항목. `school_class.id`를 사용한다. 같은 교사의 여러 행 중 한 곳에만 적거나 같은 값을 반복할 수 있다. 모두 비우면 해당 학기 담임을 해제한다. |

| 인증 이메일 | 표시 이름 | 과목 코드 | 담임 학급 코드 |
| --- | --- | --- | --- |
| teacher01@example.invalid | 김서윤 | 543001 | 544001 |
| teacher01@example.invalid | 김서윤 | 543013 | |
| teacher02@example.invalid | 김서윤 | | |

다운로드 양식의 코드 셀은 텍스트 형식이다. 머리글과 셀 병합을 변경하지 않고 값으로 입력한다. 매크로·외부 링크·수식은 사용할 수 없다. 업로드 상한은 5 MiB다. [School42 예시 파일](../demo/school42-roster.xlsx)

## HTTP 계약

기본 경로 `B = /api/schools/{schoolId}/roster/terms/{termId}/imports`. 아래에서 `schoolId=540001`, `termId=547001`은 School42 시연값이다.

| 메서드 · 경로 | 요청 | 응답 |
| --- | --- | --- |
| `GET B/template` | 본문 없음 | 200, `teacher-roster.xlsx` 바이너리. 선택 학교의 과목과 학기 학년도의 학급 코드표 포함 |
| `POST B/preview` | `multipart/form-data`, 파일 파트명 `file` | 200, 유효한 미리보기. 행·열 오류가 있으면 422와 오류 목록. 이 단계에서 학교 명부·재직·수업은 바뀌지 않는다. |
| `POST B/{previewId}/confirm` | JSON `{"fileSha256":"<미리보기의 64자리 소문자 해시>"}` | 200, 확정 영수증. 생성한 관리자만 같은 학교·학기에서 확정할 수 있다. |

미리보기 예시에서 ID·해시는 예시값이다. `counts`는 교사 수 기준이며 `errors` 집계만 셀 오류 수다. `rows`는 각 교사의 원본 엑셀 행 번호다.

```json
{
  "previewId": "018a2db7-efee-4f30-806d-ae6cba033b72",
  "fileSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "counts": {"add":1,"update":0,"unchanged":0,"errors":0},
  "changes": [{
    "authEmail":"teacher01@example.invalid","rows":[2,3],"action":"ADD","before":null,
    "after":{
      "displayName":"김서윤",
      "subjects":[{"subjectId":543001,"subjectName":"공통국어2"},{"subjectId":543013,"subjectName":"독서와 작문"}],
      "homeroomClass":{"classId":544001,"academicYear":2026,"grade":1,"classNumber":1}
    }
  }],
  "errors":[]
}
```

- `ADD`는 새 명부 항목, `UPDATE`는 이름·선택 학기 과목·담임 변경, `UNCHANGED`는 동일 상태다. 기존 항목의 `before`는 BE-R1 명부 단건과 같은 구조로 `rosterId, schoolId, authEmail, displayName, status, schoolUserId, employmentStatus, term`을 포함한다.
- 파일에 나온 교사만 선택 학기의 담당 목록을 **전체 교체**한다. 표시 이름은 학교 전체에 적용된다. 파일에서 빠진 교사와 다른 학기 배정은 유지한다. 명부 수정으로 계정을 만들거나 가입 신청을 승인하지 않는다. [BE-R1 명부 API](be-r1-roster-api.md)
- FE18은 파일·학교·학기 변경 시 기존 미리보기를 비우고 다시 업로드한다. 서버에 저장된 검증 행만 확정에 사용한다. `confirm` 요청으로 이름·배정 목록을 전송하지 않는다.
- 같은 관리자·학교·학기에서 같은 파일을 같은 원본 상태로 재검증하면 미확정 `previewId`를 재사용한다. 동시·반복 확정은 같은 영수증을 돌려준다. 확정 후 같은 파일을 새로 미리보기하면 현재 명부와 다시 비교한다.
- 각 확정은 학교 잠금과 단일 MySQL 트랜잭션에서 수행한다. 명부와 관련 과목·학급을 다시 검증하고, 담임을 서로 교환하는 경우 기존 담임을 같은 트랜잭션 내에서 해제한 후 재배정한다. 어느 한 건이라도 실패하면 명부 변경과 영수증 저장이 함께 롤백된다.
- 미확정본의 유효 시간은 24시간이다. 다음 유효한 미리보기 생성 시 그 학교의 만료본을 정리한다. 확정 후 입력 행은 제거하고 재시도에 필요한 영수증을 유지한다.

확정 영수증은 `{"previewId":"…","fileSha256":"…","counts":{"add":1,"update":0,"unchanged":0,"errors":0}}` 형태다. 확정 후 `GET /api/schools/{schoolId}/roster?termId={termId}`로 재조회할 수 있다.

## 실패 응답과 화면 처리

파일 안의 같은 교사가 C3에 중복 과목을 입력하면 HTTP 422가 반환된다. 오류 없는 다른 교사는 미리보기에 남을 수 있으나 파일 전체의 `previewId`는 null이다.

```json
{
  "previewId": null,
  "fileSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "counts": {"add":0,"update":0,"unchanged":0,"errors":1},
  "changes":[],
  "errors":[{"code":"DUPLICATE_SUBJECT","sheet":"교사명부","row":3,"column":"C","cell":"C3","message":"같은 교사의 과목 코드가 중복되었습니다."}]
}
```

| HTTP | 대표 코드 | 처리 |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | 파일 파트, UUID 또는 해시 형식을 확인한다. |
| 403 | `FORBIDDEN` | 활성 학교·현재 재직·관리자 권한을 확인한다. |
| 404 | `NOT_FOUND` | 다른 학교의 학기 또는 이 관리자·학교·학기에 속하지 않는 미리보기다. 이미 정리된 만료본도 새로 검증한다. |
| 409 | `FILE_CHANGED`, `STALE_ROSTER`, `PREVIEW_EXPIRED` | 동일 파일인지 확인하고 새 미리보기를 받는다. 서버는 기존 영수증을 재시도하는 경우 원본을 재적용하지 않는다. |
| 413 / 415 | `WORKBOOK_LIMIT`, `XLSX_REQUIRED`, `UNSUPPORTED_WORKBOOK` | 파일 크기와 일반 .xlsx 형식을 확인한다. |
| 422 | `INVALID_HEADER`, `INVALID_CELL`, `INVALID_EMAIL`, `INVALID_NAME`, `CONFLICTING_NAME`, `DUPLICATE_SUBJECT`, `AMBIGUOUS_UNASSIGNED`, `UNKNOWN_SUBJECT`, `UNKNOWN_CLASS`, `CONFLICTING_HOMEROOM`, `HOMEROOM_OCCUPIED` 등 | 표시된 시트·행·열을 고친 뒤 파일 전체를 다시 올린다. |

공통 권한·충돌 오류 예시는 `{"success":false,"error":"STALE_ROSTER","message":"명부 또는 코드표가 변경되었습니다. 다시 검증하세요.","data":null}`이다. 시트를 찾을 수 없거나 통합 문서 자체를 열 수 없으면 `{code,message,errors}` 형태로 파일 단위 사유를 반환한다.

## DB 적용과 검증

기존 기본 테이블, M1·M3·BE-R1과 BE-A2 패스키 SQL을 적용한 뒤 [V20261125_22__roster_import_preview.sql](../src/main/resources/db/migration/V20261125_22__roster_import_preview.sql)을 적용한다. 신규 테이블은 미리보기·확정 영수증만 저장한다. BE-R1의 명부 키와 학기별 저장 함수를 재사용한다.

School42 예시는 원본 ZIP의 `teacher_roster.csv`에서 42명과 담임을, `school_seed.sql`에서 인증 이메일·35개 과목 코드·21개 학급 코드를 가져왔다. `example_week.csv`에서 교사별 과목 집합을 추출해 합성 명부의 초깃값으로 사용했다. 마지막 교사의 표시 이름을 첫 교사와 같게 바꾸고 마지막 교사의 담당 과목을 비워 예외 사례를 만들었다. 총 143행이며 결과는 교사 42명, 과목 연결 142건, 복수 과목 41명, 미배정 1명, 담임 21명, 같은 이름 2명이다.

[시연 DB 실행 안내](../demo/README.md)의 격리 MySQL·Redis와 애플리케이션 환경 변수를 준비한다. 저장소 루트에서 다음을 실행한다.

```bash
R2_MYSQL_TEST=1 ./gradlew test --tests '*RosterImportMySqlTest' --rerun-tasks
BE_A1_AUTH_TEST=1 R1_MYSQL_TEST=1 R2_MYSQL_TEST=1 M3_MYSQL_TEST=1 M2_FULL_BENCHMARK=1 ./gradlew test --rerun-tasks
```

[통합 테스트](../src/test/java/com/rssolplan/edu/domain/roster/bulk/RosterImportMySqlTest.java)는 내장 Tomcat의 실제 HTTP와 MySQL을 쓴다. 반복 실행 가능한 School42 테스트는 동일 카탈로그를 격리 학교에 복제하고 코드 ID만 해당 학교의 값으로 치환한다. 중간 실패 테스트는 두 번째 쓰기에 예외를 주입한 뒤 첫 번째 쓰기와 확정 영수증이 함께 롤백되는지 실제 DB에서 확인한다.

## 학급별 담임·부담임

이미 편성용 교사 소속이 등록된 학교에서는 시수표와 같은 XLSX의 `학급담당` 시트를 업로드할 수 있다. 관리자 도구 → 명부 일괄 등록 → 학급 담당에서 학교·학기를 확인하고 **담임·부담임 적용**을 누른다. 같은 파일의 `시수표` 시트는 시간표 생성 화면에서 검증한다.

| 학년 | 반 | 학급 ID | 담임 교사 ID | 담임 이름 | 부담임 교사 ID | 부담임 이름 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 1 | 544001 | 542001 | 김서윤 | 542022 | 이도윤 |

표의 첫 행과 같은 머리글을 사용한다. 데이터 행에는 선택한 학교와 학년도의 학급을 한 번씩 모두 포함한다. 학급 ID는 `school_class.id`, 교사 ID는 해당 학교에 재직 중인 편성용 `school_user.id`다. 교사가 여러 과목을 담당해도 이 시트에는 한 번만 등장한다. 이름 열은 파일을 읽는 사람에게 제공하는 표시값이다. 서버는 ID로 연결하고 응답에는 현재 학교 명부의 이름을 표시한다. School42에서 가입 계정과 연결된 교사는 원래 수업을 담당하는 소속 ID를 사용한다.

| 요청 | 결과 |
| --- | --- |
| `POST /api/v2/terms/{termId}/homerooms/import` | 활성 학교의 재직 관리자가 멀티파트 `file`을 보낸다. 전체 시트가 유효하면 단일 트랜잭션에서 해당 학기의 학급 담당을 저장하고 200 조회 결과를 반환한다. 같은 배정을 재적용하면 기존 행을 보존한다. |
| `GET /api/v2/terms/{termId}/homerooms` | 같은 학교에 재직 중인 교직원이 학년·반 순으로 학급별 배정을 조회한다. |
| `GET /api/school/teachers?termId=…`, `GET /api/school/members?termId=…` | 학급담당 파일이 적용된 학기는 `termHomeroomsApplied:true`다. 이 때 편성용 교사 행의 `homeroomClasses`, `deputyHomeroomClasses`를 사용한다. 기존 교사·관리자 조회 권한을 따른다. |

조회 응답은 `[{classId,academicYear,grade,classNumber,homeroomTeacher,deputyTeacher}]`이다. 각 교사는 `{schoolUserId,displayName}`이며 미배정은 `null`이다. 해당 학기에 학급 담당을 올린 적이 없으면 기존 학기 명부의 담임을 우선하고, 없는 학급은 학년도 담임을 표시한다. 구 명부의 미연결 교사는 표시 이름을 반환하고 소속 ID는 `null`이다. 신규 학기 배정은 계정, 패스키, 학교별 표시 이름, 가입 연결, 과목 및 다른 학기의 역할을 변경하지 않는다.

수업 연결에는 D/F열의 편성용 교사 ID를 사용한다. E/G열 이름은 그 ID의 현재 학교 명부 표시 이름과 대조한다. 별도 로그인 계정이 기존 편성 소속에 연결된 경우 그 편성 소속의 기존 이름도 같은 ID에 한해 허용한다. 대소문자, 공백, 유니코드 표기는 시수표와 같은 기준으로 정규화한다. 이름이 다르면 422 `TEACHER_NAME_MISMATCH`와 오류 행·셀, 입력값 및 현재 명부 이름을 반환한다. 명부에 기록되지 않은 과거 이름은 해당 셀을 현재 이름으로 바꾼다. 이름만 입력하는 시수표는 한 이름이 여러 ID에 일치하면 `AMBIGUOUS_TEACHER`를 반환한다.

선택한 학기의 교사 목록과 프로필은 `homeroomAssignmentTermId`에 해당하는 `homeroomClasses`와 `deputyHomeroomClasses`를 사용한다. 담임은 학급별로 명시한 학기 역할, 학기 명부 담당, 학년도 담임 순으로 결정한다. 파일 적용 후 추가된 반도 같은 기준으로 조회한다. 같은 파일을 다시 적용해도 담당 소속 ID와 로그인 연결은 유지된다.

다른 학교의 학기는 404, 권한 부족은 403이다. 다른 학교의 교사 ID, 학년·반 불일치, 누락/중복 학급 또는 교사 중복은 422이며 `fieldErrors`의 시트·셀·메시지를 확인한다. 어떤 행이라도 실패하면 기존 배정을 보존한다.

기존 M1·M3·R1 및 교사 연결 스키마 이후 [V20261127_43__term_class_teacher_role.sql](../src/main/resources/db/migration/V20261127_43__term_class_teacher_role.sql)을 앱과 동일한 DB에 적용한다. 신규 테이블을 생성하며 데이터 이관은 필요하지 않다. 격리 시연 설정은 이 파일을 포함한다. 운영에서 시작 시 실행할 스키마 경로를 명시하는 경우 기존 경로와 순서를 유지하고 마지막에 이 파일을 추가한다.

Railway에서 기존 `SPRING_SQL_INIT_SCHEMA_LOCATIONS`를 유지하며 추가할 때는, 다른 데이터 초기화 파일이 구성되지 않았는지 먼저 확인한다. 이 경우 백엔드 서비스에 `SPRING_SQL_INIT_DATA_LOCATIONS=optional:classpath:db/migration/V20261127_43__term_class_teacher_role.sql` 한 항목을 추가하면 기존 스키마 스크립트 다음에 이 DDL이 실행된다. `optional:`은 이전 JAR에 아직 파일이 없는 배포와도 호환된다. 배포 뒤 같은 앱 DB에서 `SHOW CREATE TABLE term_class_teacher_role` 또는 재직 계정의 학급 담당 조회로 반영을 확인한다.
