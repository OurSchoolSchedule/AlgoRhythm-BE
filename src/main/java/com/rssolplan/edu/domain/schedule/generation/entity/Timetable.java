package com.rssolplan.edu.domain.schedule.generation.entity;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolClass;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.Subject;
import com.rssolplan.edu.domain.school.setting.PeriodSetting;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "timetable",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_class_period_schedule",
                        columnNames = {"school_class_id", "academic_year", "semester", "day_of_week", "period_setting_id"}),
                @UniqueConstraint(name = "uk_teacher_period_schedule",
                        columnNames = {"teacher_id", "academic_year", "semester", "day_of_week", "period_setting_id"})
        })
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Timetable {

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_class_id", nullable = false)
    private SchoolClass schoolClass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "period_setting_id", nullable = false)
    private PeriodSetting periodSetting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private DayOfWeek dayOfWeek;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private SchoolUser teacher;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
