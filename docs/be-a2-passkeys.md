# BE-A2 패스키 API · FE17 인계

이메일 인증을 마친 사용자는 패스키를 등록하거나 기존 패스키로 로그인한다. 학교 업무용 액세스·리프레시 토큰은 패스키 등록 또는 인증 완료 응답에서 받는다. 기존 이메일 계정도 같은 흐름을 사용하며 계정 ID, 표시 이름, 연락처, 학교 관계를 보존한다. 인증 이메일은 BE-R1이 저장한 검증 이메일 연결 또는 기존 이메일 제공자의 `provider_id`로 찾는다. 마이페이지의 수정 가능한 연락처를 로그인 식별자로 사용하지 않는다.

## 환경과 DB

| PASSKEY_ENVIRONMENT | 고정 프런트 origin | RP ID |
| --- | --- | --- |
| production (기본) | https://ourschoolschedule.vercel.app | ourschoolschedule.vercel.app |
| preview | https://algo-rhythm-git-fe-develop-sihyes-projects.vercel.app | algo-rhythm-git-fe-develop-sihyes-projects.vercel.app |
| local | http://localhost:5173 | localhost |

프런트는 표의 정확한 주소에서 상대 경로 `/api`를 사용한다. 커밋별 Preview 주소는 허용 목록에 없다. Preview의 rewrite는 `PASSKEY_ENVIRONMENT=preview`인 별도 백엔드를 가리켜야 한다. 운영과 Preview에 별도의 MySQL·Redis·JWT 서명 키를 사용한다. 동일 테스트 인프라에서도 RP ID로 DB·Redis가 분리되고 JWT 발급자가 일치해야 한다. 요청의 Host·Origin으로 RP 설정을 바꾸지 않는다. 최상위 문서에서 WebAuthn을 호출한다.

배포 전에 기존 `users` 테이블을 확인하고 [V20261125_21__passkey_auth.sql](../src/main/resources/db/migration/V20261125_21__passkey_auth.sql)을 적용한다. 스크립트는 RP별 계정·자격 증명 테이블을 추가하며 기존 사용자 데이터를 갱신하지 않는다. BE-R1의 `V20261125_11`과 별도다. 데모는 `demo/local.yml`에서 함께 적용한다. 이 변경 이후 기존 형식의 JWT는 다시 로그인해야 한다. FE17 로그인 화면과 환경별 rewrite가 준비된 시점에 전환한다.

## 최초 등록과 재로그인

1. 기존 `POST /api/auth/email-verification/send`로 인증 코드를 보낸 뒤 `/api/auth/email-verification/verify`에 `{"email":"…","code":"…"}`를 보낸다. 성공 본문은 `{"success":true,"message":"…","nextStep":"REGISTER","flowToken":"…","userId":42,"newUser":false}` 형식이다. 최초 등록은 `REGISTER`, 이미 등록한 계정은 `AUTHENTICATE`다. 이 값은 기존 학교 온보딩 분기와의 호환을 위해 유지한다. 이때 받은 flowToken으로 현재 기기에서 새 패스키를 등록할 수 있다. 이전 기기의 패스키나 복구 코드는 필요하지 않다. 등록 이력만으로 현재 브라우저의 패스키 보유 여부를 판단하지 않는다. 평소 패스키 로그인에는 이메일 단계가 없다.
2. 아래 표의 options 응답은 `{"ceremonyId":"…","publicKey":{…}}`다. 등록은 `PublicKeyCredential.parseCreationOptionsFromJSON(publicKey)`, 인증은 `parseRequestOptionsFromJSON(publicKey)`로 변환한 뒤 `navigator.credentials.create({publicKey})` 또는 `navigator.credentials.get({publicKey})`를 호출한다. 전송 형태는 `{"ceremonyId":"…","credential":credential.toJSON()}`이다. 브라우저 지원 여부와 취소를 확인하고 재시도 시 새 options를 받는다.
3. 등록 완료 시 새 웹 클라이언트는 `includeRecoveryCode: false`를 함께 보내고 업무 세션으로 바로 진입한다. 이 필드를 생략하는 기존 클라이언트는 최초 등록·복구 시 기존 발급 계약을 사용한다. 등록 옵션의 `user.name`과 `user.displayName`은 인증 이메일이고 `user.id`는 RP별 임의 핸들이다.
4. 관리 열쇠를 누를 때마다 `VERIFY`로 재인증한다. 성공한 로그인·확인 응답의 `passkeyUser`는 인증 이메일과 핸들이 연결된 계정에서 제공한다. 지원하는 패스키 관리자는 이 값을 `PublicKeyCredential.signalCurrentUserDetails`로 받아 저장한 이름을 갱신할 수 있다.

이하 경로 앞에 `/api/auth/passkeys`를 붙인다. ‘업무’는 `Authorization: Bearer <accessToken>`이다. 제한 세션·challenge·재인증 승인은 각각 5분간 한 번 사용한다. 등록·인증의 UV는 필수다.

| 메서드와 경로 | 요청·권한 | 성공 응답 |
| --- | --- | --- |
| POST /registration/options | 새 이메일 확인의 `{flowToken}`. 기존 패스키 보유 계정도 허용 | 등록 options |
| POST /registration/finish | `{ceremonyId,credential}`. 선택 필드 `includeRecoveryCode`. 기기 추가는 같은 계정의 업무 토큰도 전달 | 업무 세션 |
| POST /authentication/options | 공개, 본문 없음 | 사용자 이름이 필요 없는 인증 options |
| POST /authentication/finish | `{ceremonyId,credential}`. 재인증에는 같은 계정의 업무 토큰도 전달 | 로그인은 업무 세션, VERIFY는 `{verified:true, passkeyUser}`, 변경 승인은 `{approvalToken}` |
| GET /credentials | 업무 | `[{"id":"<base64url>","createdAt":"…"}]` |
| POST /reauthentication/options | 업무, `{"operation":"VERIFY"}`, `{"operation":"ADD"}`, `{"operation":"DELETE","credentialId":"…"}` 또는 `{"operation":"ROTATE"}` | 해당 계정의 UV 인증 options |
| POST /credentials/options | 업무, ADD 재인증의 `{approvalToken}` | 추가 등록 options. /registration/finish로 완료 |
| DELETE /credentials/{id} | 업무, 해당 id에 대한 DELETE 재인증의 `{approvalToken}` | 204 |
| POST /recovery-code | 업무, ROTATE 재인증의 `{approvalToken}` | `{recoveryCode}`. 기존 코드 즉시 무효 |
| POST /recovery/options | 등록한 계정의 새 이메일 확인 `{flowToken,recoveryCode}` | 복구용 등록 options. /registration/finish로 완료 |

현재 기기에서 패스키를 사용할 수 없으면 이메일을 다시 인증하고 `/registration/options` → `/registration/finish`로 새 패스키를 등록한다. 새 등록은 기존 계정 ID·사용자 핸들·학교 관계와 다른 패스키를 보존한다. 이메일 확인이나 options 호출만으로는 업무 토큰을 발급하지 않으며, 새 키의 사용자 검증(UV) 서명 검증까지 성공해야 로그인된다. 취소·실패하면 새 이메일 인증부터 다시 시작한다. 성공한 등록은 계정 revision을 갱신해 다른 미완료 등록 흐름을 무효화한다. 새 웹 클라이언트는 `includeRecoveryCode: false`를 보내므로 복구 코드를 별도 보관할 필요가 없다. 보조 패스키가 있으면 평소처럼 로그인한 뒤 분실한 키를 철회한다. 동기화된 패스키는 해당 비밀번호 관리자에서 사용할 수 있다. 기존 발급 코드를 가진 사용자는 이메일 확인과 코드로 복구할 수 있다. 기존 코드용 `/recovery/options`는 이전 클라이언트 호환을 위해 유지하며, 이 경로로 복구한 경우에만 새 키 등록과 함께 이전 키 및 사용한 코드가 무효화된다. 새 이메일 등록 화면은 이 경로를 호출하지 않는다. 등록을 취소하면 기존 키와 코드는 유지된다. 마지막 키의 단순 삭제는 거절한다. 복구 코드는 256비트 난수이며 DB에는 해시와 만료 시각(발급 후 365일)만 저장한다. 자격 증명 DB에는 임의 사용자 핸들, 자격 증명 ID, COSE 공개키, 서명 카운터와 백업 플래그, 생성 시각을 저장한다. 개인키·PIN·생체 데이터는 수집하지 않는다.

## 실패 응답과 기존 호출자

| HTTP | 예시와 FE 처리 |
| --- | --- |
| 400 | `{"code":"PASSKEY_INVALID","message":"패스키 확인에 실패했습니다."}`. 서명·origin·RP·UV 오류나 유효하지 않은 복구 코드. 새 options에서 다시 시작한다. |
| 403 | 패스키 등록 계정이 카카오로 업무 세션을 요청하면 `PASSKEY_REQUIRED`. 패스키 로그인 또는 새 이메일 인증 후 등록으로 전환한다. 업무 토큰이 필요한 경로의 미인증 403은 Spring Security 응답이다. |
| 409 | `PASSKEY_FLOW_EXPIRED`는 흐름 만료·재사용·기기 상태 변경이다. 자동 재전송하지 않고 새 흐름을 시작한다. `LAST_PASSKEY`는 새 키를 먼저 추가하도록 안내한다. |
| 401 | 철회한 키에서 발급한 refresh token으로 갱신하면 다시 로그인해야 한다. 기존에 검증을 마친 access token도 해당 키가 철회되면 업무 API에 사용할 수 없다. |

`frontend/src/api/auth.js`의 이메일 검증 호출과 `DevLoginView.jsx`의 성공 분기를 FE17에서 위 계약에 연결한다. `POST /api/auth/refresh-token`은 Authorization 헤더의 refresh token을 사용한다. 카카오 콜백은 해당 환경의 고정 프런트 origin + `/auth/kakao/callback`으로만 복귀한다. 패스키가 등록된 계정의 카카오 콜백 및 앱 로그인은 업무 토큰을 발급하지 않는다.

**BE-R1 명부 연동.** 병합된 PR #15의 `VerifiedRosterSignup.complete(email, authenticatedUserId, schoolCode, displayName)`가 반환한 `signup.user().getId()`를 같은 트랜잭션에서 `PasskeyService.afterVerifiedEmail(...)`에 넘긴다. 이메일 검증 응답에는 R1의 `schoolMembership`과 `newUser`를 보존하고 A2의 `nextStep`·`flowToken`을 함께 보낸다. 첫 가입 요청은 선택 필드 `schoolCode`·`displayName`을 함께 보낼 수 있다. 명부와 일치하면 `schoolMembership.employmentStatus=HIRED`를 받고, 패스키 등록을 마쳐야 보호된 명부 API를 호출할 수 있다. 등록 계정의 비로그인 이메일 재인증은 학교 입력을 반영하지 않는다. [명부 PR #15](https://github.com/OurSchoolSchedule/AlgoRhythm-BE/pull/15)

## 검증 재현

격리한 MySQL 8과 Redis를 연결한 상태에서 저장소 루트의 `./gradlew test`를 실행한다. `BE_A2_DB_TEST=1`은 기존 사용자 전환, 기존 계정의 코드 없는 이메일 재등록 옵션, 일회용 grant 동시 요청·만료, 환경 분리와 연동 로그인 검사를 켠다. `BE_A2_BROWSER_TEST=1`은 추가로 Node.js 22 이상, Playwright와 Chromium을 사용한다. `PLAYWRIGHT_MODULE`에 설치한 Playwright 모듈 경로, `CHROMIUM_EXECUTABLE`에 브라우저 경로를 지정할 수 있다. 브라우저 검사는 로컬 Spring 서버와 MySQL·Redis, Chromium의 CTAP2 가상 인증기를 사용한다. 메일 전송과 브라우저에 표시할 HTTPS 테스트 페이지를 격리하므로 운영 메일·배포 계정을 사용하지 않는다.

실제 프런트 화면까지 연결하려면 대응 FE의 `frontend/`에서 `npm run build`를 실행한 뒤, `BE_A2_UI_TEST=1`과 `PASSKEY_FRONTEND_DIST=<frontend/dist의 절대 경로>`를 함께 지정한다. `PasskeyEntryBrowserTest`는 빌드한 화면을 로컬 API와 연결해 등록 이력이 있는 계정의 새 기기 등록·취소 후 재인증·코드 없는 재로그인을 확인한다. `BE_A2_DB_TEST=1`에는 실제 Redis의 이메일 인증 오입력 제한·동시 요청·재발송 시 예산 유지·만료 검사도 포함된다.

### 2026-11-27 · 이메일 인증 후 새 기기 등록

이메일 응답의 `REGISTER`(최초)와 `AUTHENTICATE`(등록 이력 있음) 모두 새 기기 등록을 허용한다. `nextStep`만으로 현재 기기에 패스키가 있다고 판단하지 않는다. FE는 새 패스키 발급과 기존 패스키 로그인을 함께 제공할 수 있다. 배포 직전에 발급된 `AUTHENTICATE` 응답의 유효한 email grant도 `/registration/options`에서 추가 등록으로 변환하므로 기존 키를 삭제하지 않는다. 흐름은 RP·계정·revision에 연결되며 만료·재사용은 409다. 서버는 임의의 로그인·변경 승인 토큰을 이메일 등록 권한으로 받아들이지 않는다.

이메일 인증번호 오입력은 이메일별로 유효기간 동안 최대 5회다. 한도 도달 시 현재 코드를 폐기하고 남은 시간 동안 인증을 차단한다. 재발송해도 오입력 예산은 초기화되지 않으며, 한도 전 정상 인증이 완료되거나 제한 시간이 만료되면 초기화된다. 비교·횟수 증가·소비는 Redis에서 원자적으로 실행한다.
