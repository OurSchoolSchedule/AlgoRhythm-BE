package com.rssolplan.edu.domain.voicecommand.dto;

import jakarta.validation.constraints.NotBlank;

public record VoiceCommandRequest(
        @NotBlank String text,
        String lang,
        Long schoolId,
        Long termId,
        String requestDate,
        String targetDate
) {
    public VoiceCommandRequest(String text, String lang) {
        this(text, lang, null, null, null, null);
    }
}
