package com.rssolplan.edu.domain.schedule.extrashift.dto;

import jakarta.validation.constraints.NotBlank;

public record SubstituteApprovalRequest(
        @NotBlank String action  // APPROVE | REJECT
) {}
