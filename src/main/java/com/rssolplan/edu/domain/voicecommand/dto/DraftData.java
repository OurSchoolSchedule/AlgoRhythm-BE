package com.rssolplan.edu.domain.voicecommand.dto;

public record DraftData(Long userId, ParsedIntent intent, Long schoolId, Long termId,
                        String requestDate, String targetDate) {}
