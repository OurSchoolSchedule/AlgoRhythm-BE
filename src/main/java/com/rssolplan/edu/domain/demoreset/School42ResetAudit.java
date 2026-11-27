package com.rssolplan.edu.domain.demoreset;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "school42_reset_audit", uniqueConstraints =
        @UniqueConstraint(columnNames = {"school_id", "previous_round"}))
class School42ResetAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private long schoolId;
    private long termId;
    private long previousRound;
    private long nextRound;
    private long actorId;
    private String resetMode;
    private long jobs;
    private long versions;
    private long changeRequests;
    private LocalDateTime createdAt;
}
