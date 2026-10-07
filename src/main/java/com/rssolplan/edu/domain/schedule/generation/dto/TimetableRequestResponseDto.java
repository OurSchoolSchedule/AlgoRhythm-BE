package com.rssolplan.edu.domain.schedule.generation.dto;

import com.rssolplan.edu.domain.schedule.generation.entity.TimetableRequest.TimetableRequestStatus;

import java.time.LocalDateTime;

public record TimetableRequestResponseDto(
        Long id,
        TimetableRequestStatus status,
        Long schoolId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
