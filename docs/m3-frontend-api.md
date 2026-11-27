# 프론트 연동 규격 · 시수표 검증부터 보결 승인까지

학교 담당자는 **엑셀 검증 → 후보 생성 → 한 후보 확정** 순으로 게시한다. 교사는 **날짜별 수업 조회 → 교환 신청 또는 보결 응답**을 하고 관리자가 승인한다. 아래에서 `T`는 `/api/v2/terms/{termId}`다. 모든 요청에 기존 `Authorization: Bearer <JWT>`를 사용한다. 운영 날짜는 학교 달력의 `YYYY-MM-DD`, 교시 번호는 학교 설정의 1부터 시작하는 번호다.

## 1. 시수표 검증 · M1

| 상황 | POST 경로 |
| --- | --- |
| 학기가 있음 | `T/load-imports/validate?forGeneration=true` |
| 최초 학교 설정 | `/api/v2/school/load-imports/validate?academicYear=2026&forGeneration=true` |

재직 관리자 전용. `multipart/form-data`로 필수 `file`(.xlsx), 선택 `sheet`(여러 시트면 선택 필수), 선택 `subjectAliases`(application/json 파트, 예시 `{"공수1":20}`)를 보낸다. 브라우저에서 FormData의 multipart 경계는 자동 생성된다. `forGeneration` 생략 시 기본값은 false다. 검증 결과만 미리 볼 때 사용한다.

```json
{"sheet":"시수표","dataRows":1,"totalPeriods":1,"demands":[{"teacherId":"10","subjectId":"20","classId":"30","weeklyPeriods":1,"sourceCell":"D3"}],"validatedLoadId":"375d0754-c375-42c6-b1f0-f630a9693e37"}
```

200 응답에서 교사·과목·학급 ID는 문자열이다. `validatedLoadId`는 편성 옵션을 켰을 때만 발급하며 접수 전 유효시간은 30분이다. 학교 설정 검증본은 같은 학교·학년도의 학기에서 사용할 수 있다.

```json
{"code":"INVALID_LOAD_SHEET","message":"시수표를 수정한 후 다시 업로드하세요.","fieldErrors":[{"code":"ROW_TOTAL_MISMATCH","sheet":"시수표","row":3,"column":"F","cell":"F3","message":"학급별 합은 5, 입력한 계는 9입니다."}]}
```

| HTTP | 코드와 처리 |
| --- | --- |
| 400 | MISSING_FILE, INVALID_PARAMETER, INVALID_YEAR, INVALID_ALIAS, ALIAS_CONFLICT. 첨부 파일·학년도·별칭을 수정한다. |
| 413 / 415 | WORKBOOK_LIMIT·ROW_LIMIT / XLSX_REQUIRED·UNSUPPORTED_WORKBOOK. 일반 .xlsx 파일 최대 5 MiB, 데이터 최대 5,000행이다. |
| 422 | SELECT_SHEET이면 시트명을 지정해 다시 전송한다. UNREADABLE_XLSX는 다시 저장한다. INVALID_LOAD_SHEET는 fieldErrors를 표시한다. 셀은 1행·A열부터, 최대 100건이다. |
| 403 / 404 / 503 | 관리자 권한 또는 활성 학교 확인 / 학기 확인 / LOAD_PREVIEW_UNAVAILABLE이면 임시 저장소 복구 후 재시도한다. |

셀 오류 코드에는 MISSING_HEADER, MISSING_COLUMN_HEADER, UNKNOWN_COLUMN, DUPLICATE_HEADER, CLASS_HEADER, OVERLAPPING_MERGE, MERGED_HOURS, UNKNOWN_TEACHER, AMBIGUOUS_TEACHER, UNKNOWN_SUBJECT, AMBIGUOUS_SUBJECT, UNKNOWN_CLASS, INVALID_HOURS, DUPLICATE_DEMAND, ROW_TOTAL_MISMATCH, EMPTY_DEMAND가 있다. 전체 파일 오류는 fieldErrors가 빈 배열일 수 있다.

## 2. 편성 요청과 후보 조회 · M2/M3

**권장 흐름**은 `POST T/generations`이다. 기존 동기 경로 `POST T/schedule-candidates`와 `POST /api/v2/school/schedule-candidates?academicYear=2026`도 같은 본문을 받는다. 동기 호출은 200으로 비교 결과를 직접 반환한다. 결과에 포함된 jobId는 후보 확정에도 사용한다.

```json
{"validatedLoadId":"375d0754-c375-42c6-b1f0-f630a9693e37","teachingSlots":[{"day":"MON","period":1},{"day":"TUE","period":1}],"preferences":[{"teacherId":"10","kind":"WANT","anyOf":[{"day":"MON","period":1}]}]}
```

신규 편성의 운영 요일은 MON~FRI다. 학년별로 지정하려면 검증본 UUID와 함께 아래 `gradeLastPeriods`를 보낸다. 예시는 해당 학기에 1·2학년만 있는 학교다.

```json
{
  "validatedLoadId": "375d0754-c375-42c6-b1f0-f630a9693e37",
  "gradeLastPeriods": [
    {"grade":1,"lastPeriods":{"MON":6,"TUE":6,"WED":6,"THU":6,"FRI":6}},
    {"grade":2,"lastPeriods":{"MON":7,"TUE":6,"WED":6,"THU":7,"FRI":6}}
  ]
}
```

`grade`는 학기의 학년도에 속한 실제 학급의 학년이다. 모든 학년을 한 번씩, 평일 다섯 요일을 빠짐없이 지정한다. 마지막 교시는 등록된 교시 번호이며 0은 그날 휴업이다. 서버는 마지막 교시까지 실제로 등록된 교시만 센다. `teachingSlots`도 보내면 전체 학년의 합집합과 정확히 일치해야 한다. 학년별 설정 없이 보낸 기존 평일 공통 슬롯은 모든 학급에 똑같이 적용한다. 두 설정 모두 생략하면 월~금의 모든 등록 교시를 연다. 주말·없는 학년·누락·잘못된 교시를 보내거나 한 학급의 필요 시수가 용량을 초과하면 접수 전 400을 반환한다. 초과 오류 메시지에 학급명과 초과량이 포함된다.

후보 조회는 이번 작업 시점의 `gradeLastPeriods`, 학급 ID 문자열을 학년에 대응시키는 `classGrades`(예 `{"30":1}`), 정렬된 학교 교시 번호 `registeredPeriods`, 편성 슬롯 합집합 `teachingSlots`를 함께 반환한다. 후보 표는 학년별 운영 시간 안의 빈 슬롯을 공강으로 표시한다. 기존 작업의 `gradeLastPeriods`는 null이고 학년 기능 이전 작업의 `classGrades`도 null이다. 그때의 공통 슬롯과 주말 기록은 원래대로 조회된다.

`preferences`는 선택 입력이다. WANT는 교사가 수업하고 싶은 시간, AVOID는 피하고 싶은 시간이다. anyOf 중 한 교시만 충족해도 한 건이다. 실제 불가 시간은 서버가 읽는다. [선호 점수와 입력 크기 규격](m2-schedule-candidates.md)

| 요청 | 성공 응답 | 화면 동작 |
| --- | --- | --- |
| POST T/generations | 202 `{jobId,state:"QUEUED",statusUrl,expectedPublishRev}`, Location과 Retry-After: 2 | statusUrl과 jobId를 보관한다. |
| GET T/generations/{jobId} | 200 `{jobId,state,expectedPublishRev,result,teachingSlots,gradeLastPeriods,classGrades,registeredPeriods,objective}` | QUEUED/RUNNING에서는 result=null. 약 2초 간격으로 조회한다. |
| READY | result에 동기 API와 같은 비교 결과가 담긴다. | 폴링을 중단하고 1~4개를 순서대로 표시한다. |
| INFEASIBLE / TIME_LIMIT_NO_SOLUTION / FAILED | 더 조회할 필요가 없는 종료 상태. 워커 중단 후 만료·실패 시 result가 null일 수 있다. | 각각 조건 충돌, 제한 내 후보 미발견, 처리 실패로 구분한다. |

비교 결과 예시에서 placements는 선택 안의 주간 배치다. 서버는 M1이 검증한 시수와 교사·학급 중복을 다시 검사한다.

```json
{
  "jobId":"7eb15cc6-193f-4519-9704-50035ae28371","expectedPublishRev":0,
  "validatedLoadId":"375d0754-c375-42c6-b1f0-f630a9693e37","status":"READY",
  "stopReason":"SEARCH_EXHAUSTED","detail":null,"elapsedMs":3,
  "candidates":[{"candidateId":"f272c7729cf053f271960dbfd917341c814e459f59460f539905c9493c810941",
    "score":{"wantsMet":1,"wantsTotal":1,"teachersWithNoWantMet":0,"avoidsMet":0,"avoidsTotal":0},
    "placements":[{"teacherId":"10","subjectId":"20","classId":"30","slot":{"day":"MON","period":1}}]}]
}
```

위 배열은 후보 1건을 발췌했다. 종료 사유는 SEARCH_EXHAUSTED, TOP_FOUR_AT_BOUND, CAPACITY, TIME_LIMIT 중 하나다. READY와 TIME_LIMIT가 함께 오면 제한 내 발견한 후보를 그대로 보여준다. 생성 작업은 학교별로 한 건이며 MySQL에 입력과 결과를 보관한다. 워커 임대는 12초, 갱신은 3초마다 수행하고, 재선점 시 같은 마감 시각과 새 실행 토큰을 사용한다.

## 3. 한 후보 확정 · M3

학기가 없는 학교는 `POST /api/v2/terms`에 `{"academicYear":2026,"semester":2,"startDate":"2026-08-17","endDate":"2026-12-31"}`를 보내 `201 {"termId":7}`을 받는다. 학교 경로에서 생성한 후보는 같은 학교·학년도의 첫 게시에 사용할 수 있다.

`POST T/publications`에 선택한 후보의 원본 ID를 전달한다. 배치 배열은 요청에 포함하지 않는다.

```json
{"jobId":"7eb15cc6-193f-4519-9704-50035ae28371","candidateId":"f272c7729cf053f271960dbfd917341c814e459f59460f539905c9493c810941","effectiveFrom":"2026-11-23","expectedPublishRev":0}
```

**201** `{"versionId":83,"publishRev":1,"weeklyLessons":1}`. 같은 학기·작업·후보·적용일 재전송은 같은 버전을 반환한다. 동시에 서로 다른 후보를 확정해도 한 게시만 성공한다. 서버가 최신 학교 자료를 재검사하고 주간 원본, 학기 종료까지의 날짜별 수업, 확정 영수증을 함께 저장한다. 현재 날짜별 수업은 주간 편성 요일을 반복한다. 적용 기간에 승인된 운영 변경이 있으면 덮어쓰기를 차단한다.

## 4. 날짜별 수업 · 보결 · 교환

`GET T/lessons?date=2026-11-23`은 교사와 관리자가 사용할 수 있다. 승인 화면에서는 이 응답의 **날짜 수업 id와 rev**를 함께 보관한다. v1 timetableId와는 다른 식별자다.

```json
[{"id":51,"originId":12,"termId":7,"versionId":83,"originDate":"2026-11-23","date":"2026-11-23","classId":30,"subjectId":20,"planned":{"teacherId":10,"periodId":31},"actual":{"teacherId":10,"periodId":31},"rev":0}]
```

planned는 교환 후 예정 배정, actual은 보결까지 반영한 배정이다. periodId는 DB 교시 ID다. 생성 요청의 slot.period에는 화면에 보이는 교시 번호를 사용한다. 교환은 두 수업의 날짜·교시를 교환한다. 교사·학급·과목과 원래 날짜는 해당 수업에 남는다.

`GET T/lesson-changes/{첫 날짜 수업 ID}/candidates?firstRev=0&kind=SUBSTITUTE`는 해당 날짜와 교시에 공강인 재직 교사의 `schoolUserId` 배열을 반환한다. 교사명과 과목은 `GET /api/school/teachers?termId={학기 ID}`에서 같은 ID로 조회한다. 실제 수업 담당자가 바뀌었으면 승인된 담당자를 기준으로 하고, 휴강 수업은 점유 시간을 만들지 않는다. 교사 본인이 로그인 계정으로 등록한 불가 교시는 연결된 명부 교사에게도 적용한다.

`GET /api/school/teachers`와 관리자용 `GET /api/school/members`에 `termId`를 지정하면 최신 게시 주간표에서 교사별 과목 목록 `subjects[]`와 주간 수업 수 `weeklyLessonCount`를 계산한다. 해당 학기에 게시된 주간표가 없으면 기존 시간표를 사용한다. 보결 표에는 이 과목명과 명부의 담당 과목 `rosterSubjects[]`를 중복 없이 표시한다.

`kind=SWAP&date=2026-11-24`로 조회하면 상대 날짜에서 서로 교환 가능한 **날짜 수업 ID** 배열을 반환한다. 양쪽 교사와 학급의 해당 교시에 수업 충돌이 없어야 한다. 클라이언트는 선택 날짜의 `T/lessons?date=...`에서 이 ID들이 가리키는 수업과 실제 담당 교사를 표시한다. 서버는 신청, 수락 및 최종 승인 시 현재 배정을 다시 검사한다.

| 액션 | 요청 본문 | 응답 |
| --- | --- | --- |
| POST T/lesson-changes · 관리자 보결 신청 | `{"kind":"SUBSTITUTE","firstId":51,"firstRev":0,"substituteTeacherId":19}` | 201 신청 객체 |
| POST T/lesson-changes · 담당 교사 교환 신청 | `{"kind":"SWAP","firstId":51,"firstRev":0,"secondId":52,"secondRev":1}` | 201 신청 객체 |
| GET T/lesson-changes | 본문 없음 | 200 신청 배열. 관리자는 학기 전체, 교사는 본인이 신청하거나 응답할 건이다. |
| POST T/lesson-changes/{id}/response | `{"accept":true,"expectedRequestRev":0}` | 200 갱신된 신청. 지정 교사가 수락하면 ACCEPTED, 거절하면 REJECTED. |
| POST T/lesson-changes/{id}/approval | 헤더 `Idempotency-Key: <UUID>`. 본문 `{"expectedRequestRev":1,"expectedLessonRev":0,"expectedSecondRev":1}` | 200 `{requestId,state:"APPROVED",requestRev,lessons:[...]}` |

신청 객체는 `{id,termId,kind,requester,responder,firstId,firstRev,secondId,secondRev,state,rev}`다. requester/responder는 school_user ID다. 보결의 secondId·secondRev는 null이며 승인 시 expectedSecondRev도 생략한다. **같은 승인 버튼의 네트워크 재전송은 같은 UUID**를 사용한다. 화면이 409를 받으면 날짜 수업과 신청을 다시 조회하고 새 상태를 보여준다. 이미 바뀐 신청을 최신 rev로 자동 재시도하지 않는다.

기존 보결·교환 승인 경로는 기존 역할 및 응답 포맷을 유지한다. 선택적으로 Idempotency-Key 헤더를 보낼 수 있다. 게시된 시간표와 일치하는 기존 요청은 같은 날짜 수업 버전을 검사한다. 이행 전 주간표에만 있는 수업은 기존 승인 상태와 원본 시간표 ID·날짜별 버전을 함께 관리한다. 게시 후 원본과 일치하는 수업을 찾을 수 없으면 STALE_LESSON을 표시한다.

## 5. 오류 처리

셀 검증은 `{code,message,fieldErrors}`, M3 충돌은 `{code,message,currentRev,activeJobId}`다. 기존 공통 오류는 `{success:false,error,message,data:null}`이므로 클라이언트의 식별자는 `body.code ?? body.error`를 사용한다.

| HTTP | 코드 | 화면 처리 |
| --- | --- | --- |
| 400 | INVALID_PARAMETER / BAD_REQUEST | UUID, 필수 헤더, 날짜, 시수 검증본, 교시 및 선호 입력을 확인한다. |
| 403 / 404 | FORBIDDEN / NOT_FOUND | 활성 학교·역할 또는 소속 학기·후보를 확인한다. Redis 검증본이 만료된 경우 엑셀을 다시 검증한다. |
| 409 | GENERATION_ACTIVE / JOB_NOT_READY / JOB_EXPIRED | 활성 작업 ID가 알려지면 해당 학기 작업을 조회한다. 준비되지 않은 결과는 상태를 확인한다. |
| 409 | STALE_PUBLICATION / STALE_INPUT / JOB_ALREADY_PUBLISHED / DATED_CHANGES_EXIST / TERM_EXISTS | 최신 입력 또는 게시본을 확인한다. 승인 내역이 있는 기간의 재게시에는 수동 대조가 필요하다. |
| 409 | STALE_LESSON / STALE_REQUEST / SLOT_CONFLICT / IDEMPOTENCY_KEY_REUSED | 수업·신청을 다시 조회한다. 같은 시간 교사·학급 중복 또는 실제 불가 교시는 승인할 수 없다. 같은 키를 다른 승인에 사용하면 거부된다. |
| 409 | CONFLICT | 기존 명부·교시 또는 처리 상태가 달라졌다. 응답 메시지를 표시한다. |
| 503 | GENERATION_BUSY / LOAD_PREVIEW_UNAVAILABLE | 동기 워커가 바쁘면 Retry-After 이후 재시도한다. 검증본 저장소 장애는 복구 후 재시도한다. |

## 배포 및 검증

M1 마이그레이션 다음에 [M3 추가 SQL](../src/main/resources/db/migration/V20261124_01__generation_and_lesson_operations.sql)을 적용한 뒤 API를 배포한다. 마이그레이션은 수동 적용한다. 적용 순서와 기존 테이블 대조는 [M1 운영 안내](../sql/m1-load-import.md)를 따른다.

실제 MySQL 경합 테스트는 테스트 전용 MySQL 8 DB와 기존 Spring 테스트 환경 변수에서 `M3_MYSQL_TEST=1 bash gradlew test --tests '*MilestoneThreeMySqlTest'`로 실행한다. 테스트는 합성 학교·학기를 만들고 두 DB 트랜잭션을 동시에 시작한다. 시수 미리보기 캐시는 테스트 대역을 사용하고 게시·승인·알림·이력은 MySQL에 기록한다.

### 기존 공개 보결 모집

관리자의 `POST /api/substitutes/requests`에는 기존 `timetableId`, `substituteDate`, `note`를 보낸다. 서버는 해당 학교에서 그 날짜와 교시에 수업 및 불가 교시가 없는 재직 교사에게만 초대를 보낸다. 게시된 날짜 수업이 있으면 승인된 실제 배정을 사용한다. 게시 전에는 같은 학년도·학기의 주간 수업에 해당 날짜에 최종 승인된 보결 및 교환을 적용한다. 공강 교사가 없으면 409 `SLOT_CONFLICT`를 반환한다.

초대의 수신자 ID는 수업 명부의 `school_user.id`이고 알림은 연결된 로그인 사용자가 받는다. 교사는 받은 초대를, 관리자는 학교의 모집 내역을 조회한다. 기존 초대의 로그인 소속 ID도 읽을 수 있다. 수락·최종 승인 직전에 공강과 신청 당시 수업 버전을 다시 확인한다. 초대받지 않은 계정의 응답은 403이고, 초대 후 수업이 생기면 409다. 거절 시에는 공강 여부를 요구하지 않는다.

기존 앱 DB에는 [공개 보결 수신자 마이그레이션](../src/main/resources/db/migration/V20261127_44__public_cover_recipients.sql)을 적용한다. 기존 쉼표 구분 ID와 NULL을 유지하면서 `substitute_requests.receiver_user_ids`를 TEXT로 넓힌다. 시작 시 SQL 목록을 지정한 환경에서는 기존 목록에 이 파일을 추가한다. 같은 앱 DB에서 `SHOW COLUMNS FROM substitute_requests LIKE 'receiver_user_ids'`의 Type이 `text`인지 확인한다.
