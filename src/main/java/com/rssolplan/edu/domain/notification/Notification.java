package com.rssolplan.edu.domain.notification;

import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_notifications_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_notifications_target", columnList = "target_type, target_id")
        }
)
public class Notification {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", nullable = true)
    private School school;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id")
    private User requester;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 32)
    private TargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "timetable_swap_request_id")
    private Long timetableSwapRequestId;

    @Column(name = "substitute_request_id")
    private Long substituteRequestId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(length = 64, nullable = false)
    private Type type;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum Category {
        SCHEDULE_INPUT,
        TIMETABLE_SWAP,
        SUBSTITUTE
    }

    public enum TargetType {
        TIMETABLE_SWAP_REQUEST,
        SUBSTITUTE_REQUEST,
        SUBSTITUTE_RESPONSE
    }

    public enum Type {
        SCHEDULE_INPUT_REQUEST,

        TIMETABLE_SWAP_REQUEST,
        TIMETABLE_SWAP_NOTIFY_ADMIN,
        TIMETABLE_SWAP_ADMIN_APPROVED_REQUESTER,
        TIMETABLE_SWAP_ADMIN_APPROVED_RECEIVER,
        TIMETABLE_SWAP_ADMIN_REJECTED_REQUESTER,
        TIMETABLE_SWAP_ADMIN_REJECTED_RECEIVER,

        SUBSTITUTE_REQUEST_INVITE,
        SUBSTITUTE_NOTIFY_ADMIN,
        SUBSTITUTE_ADMIN_APPROVED_TEACHER,
        SUBSTITUTE_ADMIN_REJECTED_TEACHER,
        SUBSTITUTE_FILLED_BROADCAST
    }
}
