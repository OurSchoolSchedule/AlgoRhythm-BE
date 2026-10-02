package com.rssolplan.edu.domain.voicecommand.dto;

import jakarta.validation.constraints.NotBlank;

public record VoiceCommandRequest(
        @NotBlank String text,
        String lang          // nullable, 기본값 "ko"
) {}
