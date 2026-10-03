package com.rssolplan.edu.domain.voicecommand.dto;

public record UnavailabilitySlot(
        String dayOfWeek,    // MON/TUE/WED/THU/FRI/SAT/SUN
        int periodNumber,
        String reason
) {}
