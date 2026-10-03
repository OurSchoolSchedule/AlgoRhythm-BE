package com.rssolplan.edu.domain.schedule.generation.entity;

import com.rssolplan.edu.domain.school.School;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "timetable_set",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"school_id", "academic_year", "semester"}))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimetableSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @Column(nullable = false)
    private int academicYear;

    @Column(nullable = false)
    private int semester;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
