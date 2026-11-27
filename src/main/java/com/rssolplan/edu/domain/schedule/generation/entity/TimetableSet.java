package com.rssolplan.edu.domain.schedule.generation.entity;

import com.rssolplan.edu.domain.school.School;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

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

    @ElementCollection
    @CollectionTable(name = "term_excluded_date", joinColumns = @JoinColumn(name = "term_id"))
    @Column(name = "excluded_date", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    @Builder.Default
    private Set<LocalDate> excludedDates = new HashSet<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
