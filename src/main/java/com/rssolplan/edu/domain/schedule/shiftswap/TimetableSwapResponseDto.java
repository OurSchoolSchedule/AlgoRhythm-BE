package com.rssolplan.edu.domain.schedule.shiftswap;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * TimetableSwapRequest 엔티티를 직접 직렬화하면 SchoolUser를 통해
 * email, kakaoAccessToken 등 민감 정보가 노출될 수 있으므로
 * API 응답은 이 DTO를 통해서만 반환한다.
 */
@Getter
@Builder
public class TimetableSwapResponseDto {

    private Long id;
    private Long schoolId;

    private Long requesterTimetableId;
    private LocalDate requesterDate;
    private Long requesterSchoolUserId;
    private String requesterUsername;

    private Long receiverTimetableId;
    private LocalDate receiverDate;
    private Long receiverSchoolUserId;
    private String receiverUsername;

    private String reason;
    private String status;
    private String managerApprovalStatus;
    private LocalDateTime createdAt;

    public static TimetableSwapResponseDto from(TimetableSwapRequest req) {
        return TimetableSwapResponseDto.builder()
                .id(req.getId())
                .schoolId(req.getSchool().getId())
                .requesterTimetableId(req.getRequesterTimetable().getId())
                .requesterDate(req.getRequesterDate())
                .requesterSchoolUserId(req.getRequester().getId())
                .requesterUsername(req.getRequester().getUser().getUsername())
                .receiverTimetableId(req.getReceiverTimetable().getId())
                .receiverDate(req.getReceiverDate())
                .receiverSchoolUserId(req.getReceiver().getId())
                .receiverUsername(req.getReceiver().getUser().getUsername())
                .reason(req.getReason())
                .status(req.getStatus().name())
                .managerApprovalStatus(req.getManagerApprovalStatus().name())
                .createdAt(req.getCreatedAt())
                .build();
    }
}
