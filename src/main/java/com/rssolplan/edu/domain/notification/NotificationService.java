package com.rssolplan.edu.domain.notification;

import com.rssolplan.edu.domain.notification.dto.NotificationResponseDto;
import com.rssolplan.edu.domain.schedule.substitute.SubstituteRequestRepository;
import com.rssolplan.edu.domain.schedule.substitute.SubstituteResponseRepository;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteResponse;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.shiftswap.TimetableSwapRequest;
import com.rssolplan.edu.domain.schedule.shiftswap.TimetableSwapRequestRepository;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final SchoolUserRepository schoolUserRepository;
    private final NotificationRepository notificationRepository;
    private final SchoolRepository schoolRepository;
    private final UserRepository userRepository;
    private final TimetableSwapRequestRepository timetableSwapRequestRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteResponseRepository substituteResponseRepository;

    @Transactional
    public void sendScheduleInputRequest(Long requesterId, Long schoolId, LocalDate startDate, LocalDate endDate) {

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new IllegalArgumentException("학교를 찾을 수 없습니다."));

        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new IllegalArgumentException("요청자 유저를 찾을 수 없습니다."));

        List<SchoolUser> schoolUsers = schoolUserRepository.findBySchool_IdAndEmploymentStatus(
                schoolId, SchoolUser.EmploymentStatus.HIRED);
        String periodText = formatPeriod(startDate, endDate);

        for (SchoolUser su : schoolUsers) {
            if (su.getPosition() == SchoolUser.Position.ADMIN) continue;

            Notification notification = Notification.builder()
                    .userId(su.getUser().getId())
                    .requester(requester)
                    .school(school)
                    .category(Notification.Category.SCHEDULE_INPUT)
                    .type(Notification.Type.SCHEDULE_INPUT_REQUEST)
                    .message("교감 선생님이 " + periodText + " 시간표 입력을 요청했어요.\n시간표를 기입해주세요!")
                    .isRead(false)
                    .build();

            notificationRepository.save(notification);
        }
    }

    private String formatPeriod(LocalDate startDate, LocalDate endDate) {
        return startDate.getMonthValue() + "/" + startDate.getDayOfMonth()
                + "-" +
                endDate.getMonthValue() + "/" + endDate.getDayOfMonth();
    }

    @Transactional
    public void sendTimetableInputRequest(Long requesterId, Long schoolId) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new IllegalArgumentException("학교를 찾을 수 없습니다."));
        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new IllegalArgumentException("요청자 유저를 찾을 수 없습니다."));

        List<SchoolUser> teachers = schoolUserRepository.findBySchool_IdAndEmploymentStatus(
                schoolId, SchoolUser.EmploymentStatus.HIRED);

        for (SchoolUser su : teachers) {
            if (su.getPosition() != SchoolUser.Position.TEACHER) continue;
            Notification notification = Notification.builder()
                    .userId(su.getUser().getId())
                    .requester(requester)
                    .school(school)
                    .category(Notification.Category.SCHEDULE_INPUT)
                    .type(Notification.Type.SCHEDULE_INPUT_REQUEST)
                    .message("관리자가 시간표 작성을 위해 불가 교시 제출을 요청했습니다.")
                    .isRead(false)
                    .build();
            notificationRepository.save(notification);
        }
    }

    @Transactional
    public List<NotificationResponseDto> getNotifications(Long userId) {
        // 조회 시점 기준 해당 사용자의 모든 미읽음 알림을 일괄 읽음 처리
        notificationRepository.markVisibleAsRead(userId);

        List<Notification> notifications = notificationRepository.findVisibleForUser(userId);
        List<NotificationResponseDto> dtos = new ArrayList<>();

        var responseIds = notifications.stream().map(Notification::getSubstituteResponseId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        var responses = substituteResponseRepository.findAllById(responseIds).stream()
                .collect(java.util.stream.Collectors.toMap(SubstituteResponse::getId, r -> r));
        for (Notification n : notifications) {
            var response = responses.get(n.getSubstituteResponseId());
            TimetableSwapRequest swapRequest = null;
            SubstituteRequest substituteRequest = null;

            if (n.getTimetableSwapRequestId() != null) {
                swapRequest = timetableSwapRequestRepository
                        .findById(n.getTimetableSwapRequestId()).orElse(null);
            }
            if (n.getSubstituteRequestId() != null) {
                substituteRequest = substituteRequestRepository
                        .findById(n.getSubstituteRequestId()).orElse(null);
            }

            NotificationResponseDto dto = NotificationResponseDto.builder()
                    .id(n.getId())
                    .profileImageUrl(n.getRequester() != null ? n.getRequester().getProfileImageUrl() : null)
                    .schoolName(n.getSchool() != null ? n.getSchool().getName() : null)
                    .category(n.getCategory())
                    .type(n.getType())
                    .message(n.getMessage())
                    .createdAt(n.getCreatedAt())
                    .timetableSwapRequestId(n.getTimetableSwapRequestId())
                    .substituteRequestId(n.getSubstituteRequestId())
                    .substituteResponseId(n.getSubstituteResponseId())
                    .timetableSwapStatus(swapRequest != null ? swapRequest.getStatus() : null)
                    .timetableSwapManagerApprovalStatus(swapRequest != null ? swapRequest.getManagerApprovalStatus() : null)
                    .substituteWorkerAction(response == null ? null : response.getWorkerAction())
                    .substituteManagerApproval(response == null ? null : response.getManagerApproval())
                    .substituteStatus(substituteRequest != null ? substituteRequest.getStatus() : null)
                    .isRead(n.isRead())
                    .build();

            dtos.add(dto);
        }

        return dtos;
    }
}
