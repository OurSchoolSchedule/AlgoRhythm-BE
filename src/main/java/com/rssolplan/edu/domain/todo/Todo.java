package com.rssolplan.edu.domain.todo;

import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "todo",
        indexes = {
                @Index(name = "idx_todo_school_date", columnList = "school_id, date"),
                @Index(name = "idx_todo_user_date", columnList = "user_id, date"),
                @Index(name = "idx_todo_type", columnList = "todo_type")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Todo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", nullable = false)
    private School school;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "todo_type", nullable = false, length = 20)
    private TodoType todoType;

    public enum TodoType {
        SCHOOL,     // 학교 전체 (ADMIN만 추가 가능)
        HANDOVER,   // 인수인계 (ADMIN, TEACHER 모두 가능)
        PERSONAL    // 내 할일 (본인만 가능)
    }

    @Column(nullable = false, length = 500)
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private Boolean completed = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { updatedAt = LocalDateTime.now(); }
}
