package com.rssolplan.edu.domain.schedule.extrashift.dto;

import jakarta.validation.constraints.NotBlank;

public record SubstituteRespondRequest(
        @NotBlank String action  // ACCEPT | REJECT
) {}
