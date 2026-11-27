package com.rssolplan.edu.domain.voicecommand.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record CommandPreviewResponse(
        String draftToken,
        String intentType,
        String confidence,
        String description,
        Map<String, Object> preview,
        LocalDateTime expiresAt
) {}
