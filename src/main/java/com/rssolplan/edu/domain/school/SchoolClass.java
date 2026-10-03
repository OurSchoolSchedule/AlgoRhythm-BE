package com.rssolplan.edu.domain.school;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "school_class",
        uniqueConstraints = @UniqueConstraint(columnNames = {"school_id", "academic_year", "grade", "class_number"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchoolClass {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    /** 학년도 (예: 2026) */
    @Column(nullable = false)
    private int academicYear;

    /** 학년 (1, 2, 3) */
    @Column(nullable = false)
    private int grade;

    /** 반 (1, 2, 3 ...) */
    @Column(nullable = false)
    private int classNumber;

    /** 담임 교사 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "homeroom_teacher_id")
    private SchoolUser homeroomTeacher;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
