package com.rssolplan.edu.domain.notification.dto;

import com.rssolplan.edu.domain.notification.Notification;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.shiftswap.TimetableSwapRequest;
import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationResponseDto {

    private Long id;

    private String schoolName;
    private String profileImageUrl;

    private Notification.Category category;
    private Notification.Type type;
    private String message;

    private LocalDateTime createdAt;

    private Long timetableSwapRequestId;
    private Long substituteRequestId;
    private Long substituteResponseId;

    private TimetableSwapRequest.SwapStatus timetableSwapStatus;
    private TimetableSwapRequest.ManagerApprovalStatus timetableSwapManagerApprovalStatus;
    private SubstituteRequest.SubstituteStatus substituteStatus;

    private boolean isRead;
}
