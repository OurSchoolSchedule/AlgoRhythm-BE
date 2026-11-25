# BE-R1 · 학교 교사 명부와 이메일 가입 연동

FE19는 가입 응답의 `schoolMembership.employmentStatus`로 재직 승인과 대기 화면을 구분한다. 명부의 표시 이름·담당 과목·담임은 선택한 학교의 본인 조회 API에서 읽는다. 아래 ID와 이메일은 가상 데이터다.

## 관리자 명부 API

기본 경로는 `/api/schools/{schoolId}/roster`다. 모든 호출에 `Authorization: Bearer <accessToken>`을 보낸다. 관리자는 대상 학교를 활성 학교로 선택해야 하고 해당 학교의 `school_user`가 `ADMIN` 및 `HIRED`여야 한다. `GET /me`는 본인의 신청 또는 재직 기록으로 접근한다.

| 메서드와 경로 | 요청 | 성공 응답 |
| --- | --- | --- |
| `POST /api/schools/42/roster` | `{"authEmail":"teacher42@gmail.com","displayName":"김수학"}` | 201, 명부 항목. 동일 이메일·이름 재전송은 같은 ID를 반환한다. |
| `GET /api/schools/42/roster?termId=71` | 본문 없음 | 200, 미가입·승인 대기·연결 항목 배열 |
| `GET /api/schools/42/roster/17?termId=71` | 본문 없음 | 200, 해당 항목 |
| `PUT /api/schools/42/roster/17` | `{"authEmail":"teacher42@gmail.com","displayName":"김수학 선생님"}` | 200, 수정된 항목. 이메일은 미연결 상태에서 변경할 수 있다. |
| `PUT /api/schools/42/roster/17/terms/71` | `{"subjectIds":[3,7],"homeroomClassId":15}` | 200, 지정한 학기의 전체 과목·담임을 교체한 항목. `{"subjectIds":[],"homeroomClassId":null}`로 비운다. |
| `POST /api/schools/42/roster/17/approve` | 본문 없음 | 200, 승인된 항목. 연결된 항목의 재승인도 같은 연결을 반환한다. |
| `GET /api/schools/42/roster/me?termId=71` | 본문 없음 | 200, 로그인한 교사의 학교별 본인 항목. 자신의 승인 대기 기록도 조회할 수 있다. |

다음은 학기 조회 또는 학기 편집 후의 응답 예시다. `termId`를 생략한 기본 조회는 `term: null`이다. 유효한 학기를 지정했지만 담당 과목·담임을 입력하지 않았다면 `subjects: []`와 `homeroomClass: null`을 받는다. 학급은 같은 학교·학년도여야 하며 한 학기 한 학급의 담임은 한 명이다.

```json
{
  "rosterId": 17,
  "schoolId": 42,
  "authEmail": "teacher42@gmail.com",
  "displayName": "김수학",
  "status": "LINKED",
  "schoolUserId": 801,
  "employmentStatus": "HIRED",
  "term": {
    "termId": 71,
    "subjects": [
      {"subjectId": 3, "subjectName": "수학"},
      {"subjectId": 7, "subjectName": "미적분"}
    ],
    "homeroomClass": {"classId": 15, "academicYear": 2026, "grade": 1, "classNumber": 2}
  }
}
```

| 명부의 `status` | 연결과 재직 | FE 처리 |
| --- | --- | --- |
| `UNLINKED` | 관리자 등록 완료. `schoolUserId`, `employmentStatus`는 null | 관리자가 가입 전에 학기 정보를 입력할 수 있다. |
| `PENDING` | 본인 인증 후 학교 가입 신청. 두 필드는 null | 대기 화면에 학교·이름 표시. 관리자가 승인하면 다시 조회한다. |
| `LINKED` | 기존 또는 새 재직 ID와 실제 재직 상태가 있다. | 업무 진입에는 해당 학교의 `HIRED` 여부도 확인한다. 관리자는 휴직·퇴직 상태도 조회할 수 있다. |

## 이메일 인증, 학교 선택과 승인

1. `POST /api/auth/email-verification/send`에 `{"email":"teacher42@gmail.com"}`을 보낸다. 성공하면 HTTP 200이다.
2. `POST /api/auth/email-verification/verify`에 아래 본문을 보낸다. 서버가 일회용 코드로 이메일을 검증하고 지정한 학교에서만 연결한다. 학교를 아직 선택하지 않았다면 `schoolCode`를 생략할 수 있다. 이 경우 기존 활성 학교·재직 정보가 없으면 `schoolMembership`은 null이며 다음 단계에서 학교를 선택한다.
3. 이미 인증한 계정은 최초 학교에 `POST /api/auth/onboarding` 또는 추가 학교에 `POST /api/mypage/teacher/schools`를 호출한다. 인증 이메일은 서버의 OTP 연결에서 읽는다. 전달한 표시 이름은 명부가 없을 때 신청 이름으로 사용한다. 생략하면 계정 이름을 사용한다. 기존 명부라면 관리자가 지정한 이름을 유지한다.

```json
{"email":"teacher42@gmail.com","code":"123456","schoolCode":"DEMO42","displayName":"김수학"}
```

인증 성공은 대기 여부에 관계없이 HTTP 200이다. 다음은 사전 명부가 확인된 응답의 주요 필드다.

```json
{
  "success": true,
  "accessToken": "<accessToken>",
  "refreshToken": "<refreshToken>",
  "userId": 502,
  "schoolMembership": {
    "userId": 502, "schoolUserId": 801, "schoolId": 42,
    "position": "TEACHER", "employmentStatus": "HIRED", "schoolCode": "DEMO42",
    "schoolName": "예시고", "address": null, "phoneNumber": null, "hireDate": null
  }
}
```

| 메서드와 경로 | 요청 예시 | 성공·대기 처리 |
| --- | --- | --- |
| `POST /api/auth/onboarding` | `{"role":"TEACHER","schoolCode":"DEMO42","displayName":"김수학"}` | 200 연결 또는 기존 재직 상태, 202 승인 대기. 본문은 위의 `schoolMembership` 구조다. |
| `POST /api/mypage/teacher/schools` | `{"schoolCode":"DEMO42","displayName":"김수학"}` | 201 연결 또는 기존 재직 상태, 202 승인 대기. `schoolId, schoolCode, name, address, phoneNumber, position, employmentStatus, hireDate` 반환 |
| `GET /api/mypage/teacher/schools` | 본문 없음 | 200, 교사의 기존 재직·대기 학교 배열. 학교별 `employmentStatus`를 읽는다. |
| `PATCH /api/mypage/active-school/42` | 본문 없음 | 200, 활성 학교 정보. 본인의 `HIRED` 학교만 선택할 수 있다. |
| `GET /api/school/teachers?termId=71` | 본문 없음 | 200, 활성 학교의 교사 요약. 시간표가 비어도 `displayName, rosterSubjects, rosterHomeroomClass`를 읽을 수 있다. 재직 관리자용 `GET /api/school/members?termId=71`도 같은 필드를 제공한다. |
| `GET /api/mypage/teacher/profile` · `PUT /api/mypage/teacher/profile` | 수정 예시 `{"username":"계정 이름","email":"contact@gmail.com"}` | 200, 계정 이름·프로필 연락처. 학교 표시 이름·인증 주소는 명부 조회로 읽는다. |

명부 없는 가입 요청의 응답 예시(`POST /api/auth/onboarding`, HTTP 202):

```json
{
  "userId":502,"schoolUserId":null,"schoolId":42,"position":"TEACHER",
  "employmentStatus":"PENDING","schoolCode":"DEMO42","schoolName":"예시고",
  "address":null,"phoneNumber":null,"hireDate":null
}
```

관리자는 명부 목록에서 `PENDING` 항목을 승인한다. 신청자는 자기 학교 목록 또는 본인 명부를 다시 조회한다. 이미 다른 활성 학교가 있으면 그대로 유지되므로, 연결한 학교로 전환한 후 학교 업무를 시작한다. 이메일이 여러 학교 명부에 있더라도 각 학교를 개별 선택한다. 휴직·퇴직자가 다시 인증해도 기존 재직 상태가 유지된다.

카카오 계정은 자신의 로그인 세션을 유지한 채 OTP 검증 요청에 Bearer 토큰을 보내 인증 주소를 연결할 수 있다. 검증 전에는 기존 재직 기록만 유지된다. 프로필 편집이나 카카오 연락처 수신만으로 타인 명부를 가져올 수 없다. 학교 교사 요약의 기존 `subjects`와 `weeklyLessonCount`는 시간표의 실제 배정값이다. 교사의 과목·담임 확인 화면은 명부의 `term.subjects`와 `term.homeroomClass`를 사용하고, 담임이 null일 때는 학급 전환을 숨긴다.

## 실패 응답

명부·학교 가입 서비스 오류에는 공통 형식이 적용된다. 예를 들어 다른 학교의 관리자가 명부를 읽으면 HTTP 403과 다음 본문을 받는다.

```json
{"success":false,"error":"FORBIDDEN","message":"현재 활성 학교의 재직 관리자만 명부를 관리할 수 있습니다.","data":null}
```

| HTTP · 코드 | 조건 | 호출자 처리 |
| --- | --- | --- |
| 400 `BAD_REQUEST` | 학교 코드 오류, 빈 표시 이름, 잘못된 인증 이메일, 누락·중복 과목 ID | 입력을 수정한다. |
| 403 `FORBIDDEN` | 대상 학교의 재직 관리자 권한 부족, OTP 확인 없이 새 학교 가입, 승인 대기·휴직·퇴직 학교로 전환 | 본인 신청 상태와 활성 학교를 확인한다. |
| 404 `NOT_FOUND` | 대상 학교 밖의 명부·학기·과목, 학교·학년도에 맞지 않는 학급, 조회 가능한 본인 항목 없음 | 선택 학교·학기를 다시 확인한다. |
| 409 `CONFLICT` | 동일 인증 이메일로 다른 이름 등록, 연결·대기 중 이메일 변경, 동일 학기 담임 중복, 미신청 항목 승인, 다른 계정에 연결된 인증 주소 | 최신 명부를 조회하고 기존 항목을 수정한다. |
| 400, 인증 응답의 `success:false` | OTP 불일치·만료·재사용 | 새 코드를 발급받는다. 검증 성공 후 학교 입력 오류가 발생해도 코드는 소비된다. |

보호된 API에는 유효한 Bearer 토큰이 필요하다. 명부 행의 `rosterId`, 계정의 `userId`, 재직의 `schoolUserId`는 서로 다른 키다.

## BE-R2 저장 기준과 DB 적용

- 학교와 정규화한 인증 이메일(앞뒤 공백 제거·소문자)이 원본 등록 키다. 같은 이름으로 여러 교사를 등록할 수 있다. 수업 시수표와 분리된 `roster_term`과 `roster_term_subject`에 학기별 값을 저장한다. BE-R2는 과목 코드를 현재 학교의 `subject.id`, 담임을 해당 학교·학년도의 `school_class.id`로 해석해야 한다.
- 단일 등록은 같은 키·같은 이름이면 기존 행을 반환한다. 이름 변경은 `updateIdentity`, 과목·담임 교체는 `replaceAssignment`를 사용한다. 승인 대기 행에 대한 단일 등록은 승인 처리로 이어지지 않는다. 일괄 확정 구현 시 학교 행의 잠금과 같은 트랜잭션 안에서 원본을 다시 검증하고 변경한다. 여러 HTTP 호출로 한 파일을 나누어 확정하면 부분 반영이 생긴다.
- 운영 DB는 저장소의 수동 SQL 방식으로 적용한다. 현재 스키마의 기본 JPA 테이블과 M1·M3 SQL 적용을 확인한 후 R1 SQL을 배포 전에 실행한다. R1은 테이블 4개와 고유 키·외래 키를 생성한다. 기존 테이블에 데이터를 갱신하거나 기존 이메일로 소속을 일괄 추정하는 SQL은 없다. 재실행 시 이미 생성된 테이블은 유지된다. [R1 마이그레이션](../src/main/resources/db/migration/V20261125_11__school_teacher_roster.sql) · [기본 DB와 M1 적용](../sql/m1-load-import.md)
- 시연 환경에서는 `demo/local.yml`이 기본 테이블 → M1 → M3 → R1 → 시연 데이터 순서로 실행한다. PowerShell에서 시연용 MySQL과 Redis를 시작한 뒤 저장소 루트의 `.\demo\local.ps1 test`로 격리 DB에 전체 테스트를 실행할 수 있다. Linux에서는 동일한 테스트 DB·Redis·인증 환경 변수를 설정하고 아래 명령을 실행한다. 기존 업무 DB를 테스트 대상으로 지정하지 않는다. [시연 환경 설정](../demo/README.md)

```bash
R1_MYSQL_TEST=1 M3_MYSQL_TEST=1 M2_FULL_BENCHMARK=1 ./gradlew test --rerun-tasks
R1_MYSQL_TEST=1 ./gradlew test --tests '*TeacherRosterMySqlTest' --rerun-tasks
```

R1 통합 시험은 Spring MVC·JWT·Redis의 이메일 코드와 실제 SQL을 사용한다. 외부 메일 발송만 테스트 대역이 처리한다. 플래그를 생략한 테스트 실행에서는 조건부 DB 사례가 건너뛰어질 수 있다. [통합 테스트](../src/test/java/com/rssolplan/edu/domain/roster/TeacherRosterMySqlTest.java)

### 기존 데이터 대조 결과 · 2026-11-25

`develop@17811f4` 기준 데이터를 둔 격리 DB에서 R1 SQL을 두 번 적용했다. 매 적용 후 모든 열을 비교했을 때 `users` 80건과 `school_user` 81건의 내용·ID가 모두 일치했다. 합성 사례에는 이메일 앞부분과 같은 계정 이름, 동명이인, 두 학교 소속, 관리자·교사, 재직·휴직·퇴직과 입사일이 포함됐다. 실행 환경은 Debian 13, Amazon Corretto 17.0.21.1, Gradle 8.14.3, MySQL Community 8.4.7(InnoDB), Redis 7.2.5였다.
