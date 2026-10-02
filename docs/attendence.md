  ---

API 명세

POST /api/substitutes/requests

교감/교장: 보결 요청 생성 · @OwnerOnly

// Request
{ "timetableId": 42, "substituteDate": "2026-10-10", "note": "연수 출장" }

// Response 201
{
"id": 1, "schoolId": 3, "timetableId": 42,
"dayOfWeek": "MON", "periodNumber": 3,
"substituteDate": "2026-10-10",
"status": "OPEN", "note": "연수 출장",
"createdAt": "2026-10-02T09:00:00"
}

  ---

PATCH /api/substitutes/requests/{requestId}/response

교사: 보결 수락 또는 거절

// Request
{ "action": "ACCEPT" }   // or "REJECT"

// Response 200
{
"requestId": 1, "responseId": 7,
"candidateSchoolUserId": 15, "teacherName": "홍길동",
"teacherAction": "ACCEPT", "managerApproval": "PENDING",
"createdAt": "2026-10-02T10:30:00"
}

  ---

PATCH /api/substitutes/responses/{responseId}/approval

교감/교장: 교사 응답 최종 승인 또는 거절 · @OwnerOnly

// Request
{ "action": "APPROVE" }   // or "REJECT"

// Response 200
{
"requestId": 1, "responseId": 7,
"requestStatus": "FILLED",
"teacherAction": "ACCEPT", "managerApproval": "APPROVED"
}

  ---

GET /api/substitutes/requests?status=OPEN

교사: 현재 활성 학교의 보결 요청 목록 조회

┌──────────┬────────┬────────┬─────────────────────────────────────┐
│ 파라미터 │  타입  │ 기본값 │               허용값                │
├──────────┼────────┼────────┼─────────────────────────────────────┤
│ status   │ String │ OPEN   │ OPEN · FILLED · CANCELLED · EXPIRED │
└──────────┴────────┴────────┴─────────────────────────────────────┘

// Response 200
[
{ "id": 1, "schoolId": 3, "timetableId": 42, "dayOfWeek": "MON",
"periodNumber": 3, "substituteDate": "2026-10-10",
"status": "OPEN", "note": "연수 출장", "createdAt": "2026-10-02T09:00:00" }
]
]

