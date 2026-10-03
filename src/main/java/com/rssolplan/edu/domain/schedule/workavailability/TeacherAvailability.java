package com.rssolplan.edu.domain.schedule.workavailability;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.school.SchoolUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "teacher_availability",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_teacher_avail",
                columnNames = {"school_user_id", "day_of_week", "period_number"}))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeacherAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_user_id", nullable = false)
    private SchoolUser schoolUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private DayOfWeek dayOfWeek;

    @Column(nullable = false)
    private int periodNumber;

    private String reason;

    private LocalDateTime createdAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); }
}
