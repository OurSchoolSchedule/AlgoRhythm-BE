package com.rssolplan.edu.domain.schedule.extrashift;

import com.rssolplan.edu.domain.notification.Notification;
import com.rssolplan.edu.domain.notification.NotificationRepository;
import com.rssolplan.edu.domain.schedule.extrashift.dto.*;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest.SubstituteStatus;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteResponse;
import com.rssolplan.edu.domain.schedule.generation.TimetableRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SubstituteService {

    private final SubstituteRequestRepository requestRepo;
    private final SubstituteResponseRepository responseRepo;
    private final TimetableRepository timetableRepo;
    private final SchoolRepository schoolRepo;
    private final SchoolUserRepository schoolUserRepo;
    private final NotificationRepository notificationRepo;
    private final UserRepository userRepository;
    private final AuthorizationService authService;

    // 교감/교장: 보결 요청 생성
    @Transactional
    public SubstituteRequestDetail create(Long adminUserId, SubstituteCreateRequest req) {
        User requester = userRepository.findById(adminUserId)
                .orElseThrow(() -> new NotFoundException("요청자 유저를 찾을 수 없습니다."));

        Long schoolId = authService.getActiveSchoolIdOrThrow(adminUserId);
        SchoolUser admin = authService.getSchoolUserOrThrow(adminUserId, schoolId);
        School school = schoolRepo.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));

        Timetable timetable = timetableRepo.findById(req.timetableId())
                .orElseThrow(() -> new NotFoundException("시간표를 찾을 수 없습니다."));

        List<SchoolUser> candidates = schoolUserRepo.findBySchool_IdAndPosition(
                        schoolId, SchoolUser.Position.TEACHER).stream()
                .filter(t -> !t.getId().equals(timetable.getTeacher().getId()))
                .toList();

        String receiverCsv = candidates.stream()
                .map(c -> String.valueOf(c.getId()))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);

        SubstituteRequest request = SubstituteRequest.builder()
                .school(school)
                .owner(admin)
                .timetable(timetable)
                .substituteDate(req.substituteDate())
                .receiverUserIds(receiverCsv)
                .status(SubstituteStatus.OPEN)
                .note(req.note())
                .build();
        requestRepo.save(request);

        String msg = String.format("%s(%s %s교시) %s 보결 교사를 요청합니다.",
                school.getName(),
                timetable.getDayOfWeek().name(),
                timetable.getPeriodSetting().getPeriodNumber(),
                req.substituteDate());

        for (SchoolUser candidate : candidates) {
            notificationRepo.save(Notification.builder()
                    .userId(candidate.getUser().getId())
                    .school(school)
                    .category(Notification.Category.SUBSTITUTE)
                    .targetType(Notification.TargetType.SUBSTITUTE_REQUEST)
                    .targetId(request.getId())
                    .substituteRequestId(request.getId())
                    .type(Notification.Type.SUBSTITUTE_REQUEST_INVITE)
                    .message(msg)
                    .requester(requester)
                    .build());
        }

        return SubstituteRequestDetail.from(request);
    }

    // 교사: 보결 요청 수락/거절
    @Transactional
    public SubstituteResponseDetail respond(Long teacherUserId, Long requestId, SubstituteRespondRequest req) {
        User requester = userRepository.findById(teacherUserId)
                .orElseThrow(() -> new NotFoundException("요청자 유저를 찾을 수 없습니다."));

        Long schoolId = authService.getActiveSchoolIdOrThrow(teacherUserId);
        SchoolUser candidate = authService.getSchoolUserOrThrow(teacherUserId, schoolId);

        SubstituteRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new NotFoundException("보결 요청을 찾을 수 없습니다."));

        if (request.getStatus() != SubstituteStatus.OPEN) {
            throw new IllegalStateException("이미 종료된 요청입니다.");
        }
        if (responseRepo.findBySubstituteRequest_IdAndCandidate_Id(requestId, candidate.getId()).isPresent()) {
            throw new IllegalStateException("이미 응답한 요청입니다.");
        }

        SubstituteResponse.WorkerAction workerAction = parseTeacherAction(req.action());

        SubstituteResponse response = SubstituteResponse.builder()
                .substituteRequest(request)
                .candidate(candidate)
                .workerAction(workerAction)
                .managerApproval(SubstituteResponse.ManagerApproval.PENDING)
                .build();
        responseRepo.save(response);

        String msg = String.format("%s 교사가 보결 요청에 %s했습니다.",
                candidate.getUser().getUsername(),
                workerAction == SubstituteResponse.WorkerAction.ACCEPT ? "수락" : "거절");

        notificationRepo.save(Notification.builder()
                .userId(request.getOwner().getUser().getId())
                .school(request.getSchool())
                .category(Notification.Category.SUBSTITUTE)
                .targetType(Notification.TargetType.SUBSTITUTE_RESPONSE)
                .targetId(response.getId())
                .substituteRequestId(request.getId())
                .type(Notification.Type.SUBSTITUTE_NOTIFY_ADMIN)
                .message(msg)
                .requester(requester)
                .build());

        return SubstituteResponseDetail.from(response);
    }

    // 교감/교장: 교사 응답 최종 승인/거절
    @Transactional
    public SubstituteApprovalDetail approve(Long adminUserId, Long responseId, SubstituteApprovalRequest req) {
        User requester = userRepository.findById(adminUserId)
                .orElseThrow(() -> new NotFoundException("요청자 유저를 찾을 수 없습니다."));

        SubstituteResponse response = responseRepo.findById(responseId)
                .orElseThrow(() -> new NotFoundException("교사 응답 데이터를 찾을 수 없습니다."));

        SubstituteRequest request = response.getSubstituteRequest();

        if (!request.getOwner().getUser().getId().equals(adminUserId)) {
            throw new SecurityException("승인 권한이 없습니다.");
        }

        boolean approved = "APPROVE".equalsIgnoreCase(req.action()) || "APPROVED".equalsIgnoreCase(req.action());

        response.setManagerApproval(approved
                ? SubstituteResponse.ManagerApproval.APPROVED
                : SubstituteResponse.ManagerApproval.REJECTED);
        responseRepo.save(response);

        if (approved) {
            request.setStatus(SubstituteStatus.FILLED);
            requestRepo.save(request);
        }

        String msg = approved
                ? "교감/교장이 보결 요청을 승인했습니다."
                : "교감/교장이 보결 요청을 거절했습니다.";

        notificationRepo.save(Notification.builder()
                .userId(response.getCandidate().getUser().getId())
                .school(request.getSchool())
                .category(Notification.Category.SUBSTITUTE)
                .targetType(Notification.TargetType.SUBSTITUTE_RESPONSE)
                .targetId(response.getId())
                .substituteRequestId(request.getId())
                .type(approved
                        ? Notification.Type.SUBSTITUTE_ADMIN_APPROVED_TEACHER
                        : Notification.Type.SUBSTITUTE_ADMIN_REJECTED_TEACHER)
                .message(msg)
                .requester(requester)
                .build());

        return SubstituteApprovalDetail.from(request, response);
    }

    // 교사: 현재 학교 보결 요청 목록 조회
    @Transactional(readOnly = true)
    public List<SubstituteRequestDetail> getRequests(Long userId, String status) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SubstituteStatus substituteStatus = parseStatus(status);
        return requestRepo.findBySchool_IdAndStatus(schoolId, substituteStatus).stream()
                .map(SubstituteRequestDetail::from)
                .toList();
    }

    private SubstituteResponse.WorkerAction parseTeacherAction(String action) {
        if (action == null) return SubstituteResponse.WorkerAction.NONE;
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "ACCEPT" -> SubstituteResponse.WorkerAction.ACCEPT;
            case "REJECT" -> SubstituteResponse.WorkerAction.REJECT;
            default -> throw new IllegalArgumentException("유효하지 않은 action입니다. ACCEPT 또는 REJECT를 사용하세요.");
        };
    }

    private SubstituteStatus parseStatus(String status) {
        try {
            return SubstituteStatus.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SubstituteStatus.OPEN;
        }
    }
}
