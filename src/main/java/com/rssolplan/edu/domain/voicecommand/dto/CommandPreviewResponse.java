package com.rssolplan.edu.domain.voicecommand.dto;

import java.time.LocalDateTime;

public record CommandPreviewResponse(
        String draftToken,
        String intentType,
        String confidence,
        String description,
        Object preview,
        LocalDateTime expiresAt
) {}
