# 로컬 시연 및 백엔드 인계

기준은 `develop`의 `58d55e3`(M3 병합)이다. Docker Desktop과 JDK 17을 준비하고 저장소 루트에서 PowerShell을 사용한다. 시연 전용 MySQL은 33306, Redis는 36379, 앱은 8080 포트를 사용한다.

## 1. 기존 DB와 분리해서 시작

```powershell
git fetch origin
git switch docs/local-demo-handoff
$env:DEMO_DB_PASSWORD = Read-Host '로컬 데모 DB에 사용할 암호'
docker compose -f demo/compose.yml up -d --wait
.\demo\local.ps1 serve
```

첫 실행 때 지정한 로컬 암호를 다음 실행에도 사용한다. 새 PowerShell 창에서는 같은 값을 다시 설정한다. 앱이 Started 상태가 되면 [Swagger UI](http://localhost:8080/swagger-ui/index.html)를 연다. **Servers에서 http://localhost:8080을 선택한다.** 기본 Swagger 설정은 운영 호스트를 먼저 표시한다. 서버를 재시작하면 시연 토큰을 다시 발급한다.

| 대상 | 초기화와 재실행 |
| --- | --- |
| 기존 개발 DB | 그대로 둔다. 평소 쓰는 DB를 비울 필요가 없다. |
| algorhythm_demo | Docker가 별도 DB를 준비한다. 앱 시작 시 Hibernate 기본 테이블 → M1 SQL → M3 SQL → 합성 명부 순으로 적용한다. 같은 데이터 볼륨에서 재시작해도 게시·승인 결과가 보존된다. |
| algorhythm_test | 같은 로컬 MySQL의 별도 DB다. 경합 테스트는 실행마다 자체 학교와 학기를 만든다. |
| 기존 develop 설정 | Hibernate는 기본 엔티티를 생성하지만 버전 SQL 자동 실행기는 없다. M1을 먼저, M3를 다음에 현재 DB에 적용한다. 예전 sql/createDB.sql은 school_schedule을 직접 선택하고 user에 샘플을 넣는다. 현재 앱은 users를 사용한다. |

SQL 오류가 이미 발생했다면 첫 번째 오류의 코드, 테이블명, 아래 조회 결과를 확인한다. 뒤에 이어지는 FK 오류는 첫 누락에서 파생될 수 있다.

```sql
SELECT DATABASE(), VERSION();
SHOW TABLES;
SHOW CREATE TABLE school_user;
```

| 첫 오류 | 확인할 내용 |
| --- | --- |
| 1049 Unknown database / 1045 Access denied | JDBC URL의 DB명·포트·계정·암호. Compose 볼륨을 재사용할 때는 최초 설정한 암호. |
| 1146 Table doesn't exist | school 또는 users면 기본 테이블 생성 여부, generation_job이면 M3 적용 여부. M1·M3 SQL만 빈 DB에서 바로 실행하면 부모가 누락될 수 있다. |
| 1824 Failed to open the referenced table / 3780 incompatible | 오류에 나온 부모가 현재 DB에 있는지, 부모·자식 키 타입과 부호가 일치하는지 확인한다. |
| 1050 Table already exists | 예전 전체 생성 SQL을 재실행했는지 확인한다. 부분 적용된 DB는 SHOW CREATE TABLE로 정의를 비교한 뒤 이어간다. |

[기존 DB에 적용할 때의 대조 쿼리와 순서](../sql/m1-load-import.md)

## 2. 시연 계정과 파일

학교 901, 학기 9701, 1학년 2개 반, 과목 2개, 하루 3교시다. 학기는 2026-11-23~12-04이다. 첫 실행에는 게시된 수업과 보결 신청이 0건이다.

| 계정 | 역할·용도 | school_user ID |
| --- | --- | --- |
| admin1@example.invalid | 시수 검증·후보 확정·승인 | 9201 |
| admin2@example.invalid | 두 번째 관리자 승인 | 9202 |
| math@example.invalid | 김수학 · 수업 및 교환 | 9203 |
| english@example.invalid | 이영어 · 수업 및 교환 | 9204 |
| cover@example.invalid | 박보결 · 대기 교사 | 9205 |

[valid.xlsx](valid.xlsx)는 김수학·이영어 각각 3시수, 총 6시수다. [wrong-total.xlsx](wrong-total.xlsx)는 첫 교사의 E2 합계를 3에서 9로 변경했다. 두 파일 모두 실제 학생·교사 정보를 쓰지 않는다. 카카오·메일·음성 명령의 외부 호출에는 별도 서비스 설정이 필요하다.

## 3. Swagger에서 직접 시연

`T`는 `/api/v2/terms/9701`이다. 요청 헤더·필드의 전체 예시는 [프런트 연동 규격](../docs/m3-frontend-api.md)에서 확인한다.

1. 로컬에서 `POST /api/auth/dev-token`에 `{"email":"admin1@example.invalid"}`를 보낸다. 응답의 토큰 문자열만 Swagger **Authorize**에 입력한다.
2. `POST T/load-imports/validate?forGeneration=true`에 먼저 오류 엑셀을 첨부한다. **422**, `ROW_TOTAL_MISMATCH`, `E2`를 확인한다. 정상 파일로 다시 호출해 **200**, `totalPeriods=6`과 `validatedLoadId`를 복사한다.
3. `POST T/generations`에 다음 본문을 보낸다. **202**의 `statusUrl`을 약 2초마다 조회한다. `READY`가 되면 한 후보의 `candidateId`와 작업의 `jobId`, `expectedPublishRev`를 복사한다. 이 예시는 월요일 3교시로 제한해 시연 날짜에 모든 수업이 보인다.

```json
{
  "validatedLoadId": "<검증 응답 UUID>",
  "teachingSlots": [
    {"day":"MON","period":1},{"day":"MON","period":2},{"day":"MON","period":3}
  ],
  "preferences":[{"teacherId":"9203","kind":"WANT","anyOf":[{"day":"MON","period":1}]}]
}
```

4. `POST T/publications`에 `jobId`, `candidateId`, `effectiveFrom:"2026-11-23"`, 응답에서 받은 `expectedPublishRev`를 보낸다. **201**, `weeklyLessons=6`을 확인한다. 같은 요청을 재전송해 같은 버전인지 확인한다.
5. `GET T/lessons?date=2026-11-23`에서 6건과 각 수업의 `id`, `rev`를 확인한다. `POST T/lesson-changes`에 `{"kind":"SUBSTITUTE","firstId":<수업 id>,"firstRev":<수업 rev>,"substituteTeacherId":9205}`를 보낸다.
6. 박보결 토큰으로 전환해 `POST T/lesson-changes/{id}/response`에 `{"accept":true,"expectedRequestRev":<신청 rev>}`를 보낸다. 수락 후의 `rev`를 보관한다.
7. 두 관리자 중 한 명이 `POST T/lesson-changes/{id}/approval`에 `{"expectedRequestRev":<수락 후 rev>,"expectedLessonRev":<기존 수업 rev>}`와 `Idempotency-Key` 헤더(UUID)를 보낸다. **200 APPROVED** 후 날짜 수업의 `actual.teacherId=9205`와 증가한 `rev`를 확인한다. 같은 관리자의 네트워크 재전송은 같은 키·본문을 쓴다. 다른 관리자가 예전 버전과 별도 키를 보내면 **409**다. 진짜 동시 요청은 아래 MySQL 자동 테스트가 실행한다.
8. **성공하는 교환 시연**은 별도 학기로 진행한다. 관리자 토큰으로 `POST /api/v2/terms`에 `{"academicYear":2026,"semester":1,"startDate":"2026-03-02","endDate":"2026-03-13"}`을 보낸다. 반환된 학기 ID를 T에 사용한다. [swap.xlsx](swap.xlsx)는 1반 수학·영어가 각각 1시수다. 이 파일을 검증하고, teachingSlots에 월요일 1·2교시만 넣어 생성한 뒤 3월 2일부터 게시한다. 해당 날짜의 수업 2개를 조회한다. 첫 수업의 실제 담당 교사가 `kind:"SWAP"`, `firstId/firstRev/secondId/secondRev`로 신청하고 두 번째 수업 담당 교사가 수락한다. 관리자는 두 수업의 기존 버전을 `expectedLessonRev`, `expectedSecondRev`로 보내 승인한다. 200과 교시가 서로 바뀌었는지 날짜별 조회로 확인한다.

재시연 시 이미 게시된 학기는 날짜별 조회부터 이어간다. 승인된 기간을 덮어쓰려 하면 `DATED_CHANGES_EXIST`가 정상이다. 새 환경에서 처음부터 다시 연습하려면 정지한 뒤 별도의 Compose 프로젝트명(`docker compose -p algorhythm-rehearsal -f demo/compose.yml up -d --wait`)으로 새 볼륨을 사용한다. 기존 프로젝트는 `docker compose -f demo/compose.yml stop`으로 포트를 먼저 비운다.

## 4. 백엔드 자체 검증

서버를 멈춰도 MySQL·Redis 컨테이너는 실행 상태로 둔다. 같은 PowerShell에서 다음을 실행한다.

```powershell
.\demo\local.ps1 test
Invoke-Item .\build\reports\tests\test\index.html
```

스크립트는 테스트 전용 DB에 연결하고 `M3_MYSQL_TEST=1`, `M2_FULL_BENCHMARK=1`을 설정해 `gradlew.bat test --rerun-tasks`를 실행한다. 두 플래그를 생략하면 경합과 60초 성능 시험이 건너뛰어질 수 있다.

| 검증 | 통과 기준 |
| --- | --- |
| M1 엑셀 | 정상 시수·명부 일치, 병합·합계·누락 등은 행·열 오류 |
| M2 엔진 | 교사·학급 중복 0건, 검증된 주당 시수와 일치, 유효 후보 최대 4개, 시간 상한 시험 통과 |
| M3 실제 MySQL | 동시 게시·동일 수업 보결 및 교환 경합 시 하나만 반영, 재전송 시 중복 반영 없음 |
| HTTP 시연 | 실제 Redis 검증본으로 422 → 200 → 202 → READY → 201 → 승인 200/409 |

## 5. 프런트 인계

- [API 데이터베이스](https://app.notion.com/p/422c6d23b91781adaea3ca17d5547dc6)에서 역할·요청·응답을 확인한다. 전체 보기는 업무 API 84개, 변경 보기는 19개다. Actuator의 상태 확인, Swagger 자체 경로는 업무 API 수에서 제외했다.
- [OpenAPI 인계본](../docs/openapi-handoff.json)은 Postman 또는 Swagger Editor에 가져올 수 있다. 로컬 자동 문서는 `GET /v3/api-docs`로도 내려받을 수 있다. 자동 문서는 생성 접수를 200으로 표시하고 엑셀의 subjectAliases 파트를 누락하므로, 수동 대조한 인계본과 M3 규격을 사용한다.
- 프런트 개발 서버가 다른 포트에서 뜨면 개발 프록시를 통해 상대 경로 `/api`를 같은 출처로 요청한다. 현재 CORS 설정은 직접 교차 출처 요청에서 `Idempotency-Key`를 허용하지 않고 `Location`·`Retry-After`를 노출하지 않는다. 요청 접수 응답 본문의 statusUrl도 쓸 수 있다. 실제 분리 도메인 배포 전에는 헤더 설정을 반영할 필요가 있다.
