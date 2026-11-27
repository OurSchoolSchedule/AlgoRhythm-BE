package com.rssolplan.edu.domain.voicecommand.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI Function Calling 응답을 역직렬화하는 플랫 DTO.
 * intentType별로 다른 필드가 사용되며, 미사용 필드는 null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParsedIntent(
        // 공통
        String intentType,
        String confidence,         // HIGH / MEDIUM / LOW

        // SUBSTITUTE_CREATE
        Long timetableId,
        String substituteDate,     // ISO-8601: YYYY-MM-DD
        String note,

        // SUBSTITUTE_RESPOND
        Long requestId,
        String action,             // ACCEPT / REJECT / APPROVE

        // SUBSTITUTE_APPROVE
        Long responseId,

        // AVAILABILITY_ADD, AVAILABILITY_REPLACE
        List<UnavailabilitySlot> unavailabilitySlots,
        Integer periodNumber,
        String teacherName,
        String targetDate,
        String weekStart,
        String targetWeekStart
) {
    public ParsedIntent(String intentType, String confidence, Long timetableId, String substituteDate,
                        String note, Long requestId, String action, Long responseId,
                        List<UnavailabilitySlot> unavailabilitySlots, Integer periodNumber, String teacherName) {
        this(intentType, confidence, timetableId, substituteDate, note, requestId, action, responseId,
                unavailabilitySlots, periodNumber, teacherName, null, null, null);
    }

    public ParsedIntent withDates(String first, String second, String week, String targetWeek) {
        return new ParsedIntent(intentType, confidence, timetableId, first, note, requestId, action,
                responseId, unavailabilitySlots, periodNumber, teacherName, second, week, targetWeek);
    }
    public IntentType resolvedType() {
        try {
            return IntentType.valueOf(intentType);
        } catch (Exception e) {
            return IntentType.UNKNOWN;
        }
    }
}
