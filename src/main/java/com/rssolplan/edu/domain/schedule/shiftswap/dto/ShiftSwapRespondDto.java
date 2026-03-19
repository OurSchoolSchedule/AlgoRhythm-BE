package com.rssolplan.edu.domain.schedule.shiftswap.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShiftSwapRespondDto {
    private String action; // "ACCEPT" or "REJECT"
}
