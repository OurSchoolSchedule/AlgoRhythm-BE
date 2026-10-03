package com.rssolplan.edu.domain.schedule.shiftswap;

import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "timetable_swap_requests")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableSwapRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_timetable_id", nullable = false)
    private Timetable requesterTimetable;

    @Column(nullable = false)
    private LocalDate requesterDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_timetable_id", nullable = false)
    private Timetable receiverTimetable;

    @Column(nullable = false)
    private LocalDate receiverDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private SchoolUser requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private SchoolUser receiver;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SwapStatus status = SwapStatus.PENDING;

    public enum SwapStatus {
        PENDING, ACCEPTED, REJECTED, CANCELLED
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ManagerApprovalStatus managerApprovalStatus = ManagerApprovalStatus.PENDING;

    public enum ManagerApprovalStatus {
        PENDING, APPROVED, REJECTED
    }

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
