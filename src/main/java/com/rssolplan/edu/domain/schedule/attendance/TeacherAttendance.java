package com.rssolplan.edu.domain.schedule.attendance;

import com.rssolplan.edu.domain.school.SchoolUser;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "teacher_attendance",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_teacher_attendance_day",
                columnNames = {"school_user_id", "work_date"}))
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeacherAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_user_id", nullable = false)
    private SchoolUser schoolUser;

    @Column(nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    @Builder.Default
    private boolean checkedIn = false;

    private LocalDateTime checkInTime;

    @Column(nullable = false)
    @Builder.Default
    private boolean checkedOut = false;

    private LocalDateTime checkOutTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TeacherAttendanceStatus status = TeacherAttendanceStatus.BEFORE_WORK;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
