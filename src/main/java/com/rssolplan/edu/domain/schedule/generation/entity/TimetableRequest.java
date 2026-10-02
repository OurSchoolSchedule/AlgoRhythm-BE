package com.rssolplan.edu.domain.schedule.generation.entity;

import com.rssolplan.edu.domain.school.School;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "timetable_request")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TimetableRequestStatus status = TimetableRequestStatus.REQUESTED;

    public enum TimetableRequestStatus {
        REQUESTED, GENERATED, CONFIRMED
    }

    /** Redis key for temporary generation settings */
    private String temporarySettingKey;

    /** Redis key for candidate timetable set */
    private String candidateTimetableKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "timetable_set_id")
    private TimetableSet timetableSet;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
