package com.rssolplan.edu.domain.voicecommand.dto;

/** Redis에 저장되는 드래프트 데이터. confirm 시 userId 검증에 사용. */
public record DraftData(Long userId, ParsedIntent intent) {}
