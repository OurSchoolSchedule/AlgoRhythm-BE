package com.rssolplan.edu.domain.voicecommand.dto;

public enum IntentType {
    SUBSTITUTE_CREATE,      // 보결 요청 생성 (Admin)
    SUBSTITUTE_RESPOND,     // 보결 수락/거절 (Teacher)
    SUBSTITUTE_APPROVE,     // 보결 최종 승인 (Admin)
    AVAILABILITY_ADD,       // 불가 교시 추가
    AVAILABILITY_REPLACE,   // 불가 교시 전체 교체
    UNKNOWN
}
