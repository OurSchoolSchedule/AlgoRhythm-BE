package com.rssolplan.edu.domain.voicecommand.dto;

import jakarta.validation.constraints.NotBlank;

public record CommandConfirmRequest(@NotBlank String draftToken) {}
