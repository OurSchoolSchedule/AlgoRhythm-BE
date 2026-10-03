package com.rssolplan.edu.domain.school;

import com.rssolplan.edu.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "school_user",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "school_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SchoolUser {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id")
    private School school;

    @Enumerated(EnumType.STRING)
    private Position position;

    public enum Position { ADMIN, TEACHER }

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private EmploymentStatus employmentStatus = EmploymentStatus.HIRED;

    public enum EmploymentStatus { HIRED, ON_LEAVE, RESIGNED }

    @Column(name = "hire_date")
    private LocalDate hireDate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void pre() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate  void upd() { updatedAt = LocalDateTime.now(); }
}
