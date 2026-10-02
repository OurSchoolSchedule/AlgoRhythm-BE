package com.rssolplan.edu.domain.schedule.extrashift.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record SubstituteCreateRequest(
        @NotNull Long timetableId,
        @NotNull LocalDate substituteDate,
        String note
) {}
