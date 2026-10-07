package com.rssolplan.edu.domain.schedule.substitute.entity;

import com.rssolplan.edu.domain.school.SchoolUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "substitute_responses")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubstituteResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitute_request_id", nullable = false)
    private SubstituteRequest substituteRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_id", nullable = false)
    private SchoolUser candidate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private WorkerAction workerAction = WorkerAction.NONE;

    public enum WorkerAction {
        NONE, ACCEPT, REJECT
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ManagerApproval managerApproval = ManagerApproval.PENDING;

    public enum ManagerApproval {
        PENDING, APPROVED, REJECTED
    }

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
