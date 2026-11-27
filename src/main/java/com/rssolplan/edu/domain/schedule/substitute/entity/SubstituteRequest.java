package com.rssolplan.edu.domain.schedule.substitute.entity;

import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

@Getter
@Setter
@Entity
@Table(name = "substitute_requests")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubstituteRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private SchoolUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "timetable_id")
    private Timetable timetable;

    // Set together for a published occurrence. Legacy rows use timetable_id.
    private Long datedTermId;
    private Long datedLessonId;
    private Long expectedLessonRev;
    private Integer datedPeriodNumber;

    public int periodNumber() {
        return datedLessonId == null ? timetable.getPeriodSetting().getPeriodNumber() : datedPeriodNumber;
    }

    public String weekday() {
        return datedLessonId == null ? timetable.getDayOfWeek().name() : substituteDate.getDayOfWeek().name().substring(0, 3);
    }

    @Column(nullable = false)
    private LocalDate substituteDate;

    /** Comma-separated SchoolUser IDs to notify */
    @Column(columnDefinition = "TEXT")
    private String receiverUserIds;

    public boolean includesCandidate(long schoolUserId) {
        return receiverUserIds != null && Arrays.stream(receiverUserIds.split(","))
                .map(String::trim)
                .anyMatch(Long.toString(schoolUserId)::equals);
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubstituteStatus status = SubstituteStatus.OPEN;

    public enum SubstituteStatus {
        OPEN, FILLED, CANCELLED, EXPIRED
    }

    @Column(columnDefinition = "TEXT")
    private String note;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
