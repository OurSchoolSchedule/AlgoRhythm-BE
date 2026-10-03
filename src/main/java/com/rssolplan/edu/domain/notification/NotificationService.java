package com.rssolplan.edu.domain.notification;

import com.rssolplan.edu.domain.notification.dto.NotificationResponseDto;
import com.rssolplan.edu.domain.schedule.extrashift.SubstituteRequestRepository;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest;
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

    @Transactional
    public void sendScheduleInputRequest(Long requesterId, Long schoolId, LocalDate startDate, LocalDate endDate) {

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new IllegalArgumentException("학교를 찾을 수 없습니다."));

        User requester = userRepository.findById(requesterId)
                .orElseThrow(() -> new IllegalArgumentException("요청자 유저를 찾을 수 없습니다."));

        List<SchoolUser> schoolUsers = schoolUserRepository.findBySchool_Id(schoolId);
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

        List<SchoolUser> teachers = schoolUserRepository.findBySchool_IdAndPosition(
                schoolId, SchoolUser.Position.TEACHER);

        for (SchoolUser su : teachers) {
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

    @Transactional(readOnly = true)
    public List<NotificationResponseDto> getNotifications(Long userId) {

        List<Notification> notifications = notificationRepository.findByUserIdWithSchool(userId);
        List<NotificationResponseDto> dtos = new ArrayList<>();

        for (Notification n : notifications) {
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
                    .profileImageUrl(n.getRequester() != null ? n.getRequester().getProfileImageUrl() : null)
                    .schoolName(n.getSchool() != null ? n.getSchool().getName() : null)
                    .category(n.getCategory())
                    .type(n.getType())
                    .message(n.getMessage())
                    .createdAt(n.getCreatedAt())
                    .timetableSwapRequestId(n.getTimetableSwapRequestId())
                    .substituteRequestId(n.getSubstituteRequestId())
                    .timetableSwapStatus(swapRequest != null ? swapRequest.getStatus() : null)
                    .timetableSwapManagerApprovalStatus(swapRequest != null ? swapRequest.getManagerApprovalStatus() : null)
                    .substituteStatus(substituteRequest != null ? substituteRequest.getStatus() : null)
                    .isRead(n.isRead())
                    .build();

            dtos.add(dto);
        }

        return dtos;
    }
}
