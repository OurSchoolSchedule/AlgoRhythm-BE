# Resend 인증 메일 설정

인증 메일은 SMTP 대신 `https://api.resend.com/emails`로 HTTPS 요청을 보내 발송한다. API 경로와 인증 후 JWT 발급 방식은 유지한다.

## Railway 환경변수

Railway 서비스의 Variables에 아래 값을 설정한 후 재배포한다. 로컬에서는 기존 `.env`에 동일한 변수를 추가한다.

| 변수 | 설정값 |
| --- | --- |
| `RESEND_API_KEY` | 이미 발급한 Resend API 키. 이메일 발송 권한이 필요하다. |
| `RESEND_FROM_EMAIL` | Resend에서 검증한 도메인의 발신 주소. 예: `우리학교시간표 <no-reply@your-domain.kr>` |

발신 주소의 도메인은 Resend에서 검증한 도메인 또는 검증한 발신 서브도메인과 일치해야 한다. 예시의 `your-domain.kr`은 실제 보유 도메인으로 바꾼다. API 키와 실제 `.env`는 저장소에 커밋하지 않는다. 기존 `MAIL_USERNAME`, `MAIL_PASSWORD`는 더 이상 사용하지 않는다.

API 키 또는 발신 주소가 비어 있으면 설정 검증으로 애플리케이션 시작이 실패한다. 두 환경변수를 배포 전에 설정해야 한다.

`email.verification.allowed-domains`는 **수신자** 허용 도메인 목록으로, 발신 도메인과 별개다. 현재 `ewha.ac.kr`, `gmail.com`을 유지한다. `email.verification.expiration`은 인증 코드의 Redis TTL, 메일 본문, 발송 API 응답에 공통으로 적용된다(기본 5분).

## 동작 및 확인

1. `POST /api/auth/email-verification/send`에 `{"email":"teacher@ewha.ac.kr"}`를 보낸다.
2. Resend가 요청을 수락하고 메시지 ID를 반환하면 `SENT` 이력을 저장하고 성공 응답을 반환한다. 실제 수신 여부는 메일함과 Resend 발송 기록에서 확인한다.
3. 수신한 코드로 `POST /api/auth/email-verification/verify`에 `{"email":"teacher@ewha.ac.kr","code":"수신한 6자리 코드"}`를 보낸다. 인증 성공 후 코드는 삭제되며 기존 JWT 응답을 반환한다.

HTTP 오류, 통신 실패, 메시지 ID가 없는 응답은 발송 실패로 처리한다. 실패한 코드만 Redis에서 삭제하고 `FAILED` 이력을 기록한다. API 키와 Resend 오류 응답 본문은 로그나 API 응답에 노출하지 않는다. 기존 발송 실패 응답은 HTTP 500과 `success: false`를 유지한다.

연결 제한은 10초, 응답 대기는 30초다. SMTP 연결 및 Gmail DNS 점검은 제거했다. 자동 재시도는 수행하지 않으며 실패 시 사용자가 새 인증 메일을 요청할 수 있다.

요청 규격: [Resend 공식 Send Email API](https://resend.com/docs/api-reference/emails/send-email).
