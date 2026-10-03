package com.rssolplan.edu.domain.schedule.shiftswap;

import com.rssolplan.edu.domain.notification.Notification;
import com.rssolplan.edu.domain.notification.NotificationRepository;
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

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TimetableSwapService {

    private final TimetableSwapRequestRepository requestRepo;
    private final NotificationRepository notificationRepo;
    private final TimetableRepository timetableRepo;
    private final SchoolRepository schoolRepo;
    private final SchoolUserRepository schoolUserRepo;
    private final UserRepository userRepository;
    private final AuthorizationService authService;

    // 1. 교시 교환 요청 생성
    /**
     * Creates a pending swap between the user's timetable and another member's timetable
     * in the active school, and persists a notification for the receiver. Dates identify
     * the requested occurrences; the timetable assignments are not changed.
     *
     * @throws NotFoundException if the user, school, or either timetable does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active or membership is missing
     * @throws SecurityException if a timetable belongs to another school or the requester does not teach its slot
     * @throws IllegalArgumentException if both timetables belong to the requester
     */
    @Transactional
    public TimetableSwapRequest create(Long requesterUserId,
                                       Long requesterTimetableId, LocalDate requesterDate,
                                       Long receiverTimetableId, LocalDate receiverDate,
                                       String reason) {
        User reqUser = userRepository.findById(requesterUserId)
                .orElseThrow(() -> new NotFoundException("요청자 유저를 찾을 수 없습니다."));

        Long schoolId = authService.getActiveSchoolIdOrThrow(requesterUserId);
        School school = schoolRepo.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));
        SchoolUser requester = authService.getSchoolUserOrThrow(requesterUserId, schoolId);

        Timetable requesterTimetable = timetableRepo.findById(requesterTimetableId)
                .orElseThrow(() -> new NotFoundException("요청자 시간표를 찾을 수 없습니다."));
        Timetable receiverTimetable = timetableRepo.findById(receiverTimetableId)
                .orElseThrow(() -> new NotFoundException("수신자 시간표를 찾을 수 없습니다."));

        if (!requesterTimetable.getSchool().getId().equals(schoolId)
                || !receiverTimetable.getSchool().getId().equals(schoolId)) {
            throw new SecurityException("같은 학교 시간표끼리만 교환할 수 있습니다.");
        }

        if (!requesterTimetable.getTeacher().getId().equals(requester.getId())) {
            throw new SecurityException("본인의 시간표에 대해서만 교환 요청을 생성할 수 있습니다.");
        }

        SchoolUser receiver = receiverTimetable.getTeacher();

        if (receiver.getId().equals(requester.getId())) {
            throw new IllegalArgumentException("본인과는 교환할 수 없습니다.");
        }

        TimetableSwapRequest request = TimetableSwapRequest.builder()
                .school(school)
                .requesterTimetable(requesterTimetable)
                .requesterDate(requesterDate)
                .receiverTimetable(receiverTimetable)
                .receiverDate(receiverDate)
                .requester(requester)
                .receiver(receiver)
                .reason(reason)
                .status(TimetableSwapRequest.SwapStatus.PENDING)
                .managerApprovalStatus(TimetableSwapRequest.ManagerApprovalStatus.PENDING)
                .build();

        requestRepo.save(request);

        String msg = String.format("%s 교사가 %s %s교시↔%s %s교시 교환을 요청했습니다.",
                requester.getUser().getUsername(),
                requesterDate,
                requesterTimetable.getPeriodSetting().getPeriodNumber(),
                receiverDate,
                receiverTimetable.getPeriodSetting().getPeriodNumber());

        notificationRepo.save(Notification.builder()
                .userId(receiver.getUser().getId())
                .school(school)
                .category(Notification.Category.TIMETABLE_SWAP)
                .targetType(Notification.TargetType.TIMETABLE_SWAP_REQUEST)
                .targetId(request.getId())
                .timetableSwapRequestId(request.getId())
                .type(Notification.Type.TIMETABLE_SWAP_REQUEST)
                .message(msg)
                .isRead(false)
                .requester(reqUser)
                .build());

        return request;
    }

    // 2. 수신 교사: 수락/거절 1차 응답
    /**
     * Records the receiver's ACCEPT or REJECT decision on a pending swap. Acceptance
     * notifies school administrators; rejection notifies the requester. Returns the updated request.
     *
     * @param action case-insensitive ACCEPT or REJECT, without surrounding whitespace
     * @throws NotFoundException if the user or request does not exist
     * @throws SecurityException if the user is not the receiver
     * @throws IllegalStateException if the request is no longer pending
     * @throws IllegalArgumentException if the action is null or unsupported
     */
    @Transactional
    public TimetableSwapRequest respond(Long receiverUserId, Long requestId, String action) {
        User reqUser = userRepository.findById(receiverUserId)
                .orElseThrow(() -> new NotFoundException("요청자 유저를 찾을 수 없습니다."));

        TimetableSwapRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new NotFoundException("교환 요청을 찾을 수 없습니다."));

        if (!request.getReceiver().getUser().getId().equals(receiverUserId)) {
            throw new SecurityException("이 교환 요청에 응답할 권한이 없습니다.");
        }

        if (request.getStatus() != TimetableSwapRequest.SwapStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 교환 요청입니다.");
        }

        String act = action == null ? "" : action.toUpperCase();

        switch (act) {
            case "REJECT" -> {
                request.setStatus(TimetableSwapRequest.SwapStatus.REJECTED);
                notifyRequester(request, reqUser, Notification.Type.TIMETABLE_SWAP_ADMIN_REJECTED_REQUESTER,
                        "교환 요청이 상대 교사에게 거절되었습니다.");
            }
            case "ACCEPT" -> {
                request.setStatus(TimetableSwapRequest.SwapStatus.ACCEPTED);
                request.setManagerApprovalStatus(TimetableSwapRequest.ManagerApprovalStatus.PENDING);

                // 관리자에게 최종 승인 요청
                List<SchoolUser> admins = schoolUserRepo.findBySchool_IdAndPosition(
                        request.getSchool().getId(), SchoolUser.Position.ADMIN);
                for (SchoolUser admin : admins) {
                    String msg = String.format("%s 교사가 %s 교사의 교환 요청을 수락했습니다. 최종 승인해주세요.",
                            request.getReceiver().getUser().getUsername(),
                            request.getRequester().getUser().getUsername());
                    notificationRepo.save(Notification.builder()
                            .userId(admin.getUser().getId())
                            .school(request.getSchool())
                            .category(Notification.Category.TIMETABLE_SWAP)
                            .targetType(Notification.TargetType.TIMETABLE_SWAP_REQUEST)
                            .targetId(request.getId())
                            .timetableSwapRequestId(request.getId())
                            .type(Notification.Type.TIMETABLE_SWAP_NOTIFY_ADMIN)
                            .message(msg)
                            .isRead(false)
                            .requester(reqUser)
                            .build());
                }
            }
            default -> throw new IllegalArgumentException("지원하지 않는 action입니다. (ACCEPT/REJECT)");
        }

        return requestRepo.save(request);
    }

    // 3. 관리자: 최종 승인/거절
    /**
     * Records APPROVE or REJECT for an accepted swap awaiting a final decision in the
     * user's active school, and notifies both participants. Returns the updated request;
     * this operation changes approval status without changing timetable assignments.
     *
     * @param action case-insensitive APPROVE or REJECT, without surrounding whitespace
     * @throws NotFoundException if the user or request does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     * @throws SecurityException if the request belongs to another school
     * @throws IllegalStateException if the swap is not accepted or already has a final decision
     * @throws IllegalArgumentException if the action is null or unsupported
     */
    @Transactional
    public TimetableSwapRequest managerApproval(Long adminUserId, Long requestId, String action) {
        User reqUser = userRepository.findById(adminUserId)
                .orElseThrow(() -> new NotFoundException("요청자 유저를 찾을 수 없습니다."));

        TimetableSwapRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new NotFoundException("교환 요청을 찾을 수 없습니다."));

        Long schoolId = authService.getActiveSchoolIdOrThrow(adminUserId);
        if (!request.getSchool().getId().equals(schoolId)) {
            throw new SecurityException("해당 학교의 관리자만 승인/거절할 수 있습니다.");
        }

        if (request.getStatus() != TimetableSwapRequest.SwapStatus.ACCEPTED) {
            throw new IllegalStateException("수신 교사가 수락한 요청만 최종 승인/거절할 수 있습니다.");
        }
        if (request.getManagerApprovalStatus() != TimetableSwapRequest.ManagerApprovalStatus.PENDING) {
            throw new IllegalStateException("이미 최종 처리된 교환 요청입니다.");
        }

        String act = action == null ? "" : action.toUpperCase();

        switch (act) {
            case "APPROVE" -> {
                request.setManagerApprovalStatus(TimetableSwapRequest.ManagerApprovalStatus.APPROVED);

                notifyRequester(request, reqUser, Notification.Type.TIMETABLE_SWAP_ADMIN_APPROVED_REQUESTER,
                        "교시 교환이 관리자로부터 최종 승인되었습니다.");
                notifyReceiver(request, reqUser, Notification.Type.TIMETABLE_SWAP_ADMIN_APPROVED_RECEIVER,
                        "교시 교환이 관리자로부터 최종 승인되었습니다.");
            }
            case "REJECT" -> {
                request.setManagerApprovalStatus(TimetableSwapRequest.ManagerApprovalStatus.REJECTED);

                notifyRequester(request, reqUser, Notification.Type.TIMETABLE_SWAP_ADMIN_REJECTED_REQUESTER,
                        "교시 교환이 관리자로부터 거절되었습니다.");
                notifyReceiver(request, reqUser, Notification.Type.TIMETABLE_SWAP_ADMIN_REJECTED_RECEIVER,
                        "교시 교환이 관리자로부터 거절되었습니다.");
            }
            default -> throw new IllegalArgumentException("지원하지 않는 action입니다. (APPROVE/REJECT)");
        }

        return requestRepo.save(request);
    }

    // 4. 조회
    /**
     * Returns swaps where the user's membership in the active school is either participant.
     *
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active or membership is missing
     */
    @Transactional(readOnly = true)
    public List<TimetableSwapRequest> getMyRequests(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolUser me = authService.getSchoolUserOrThrow(userId, schoolId);
        return requestRepo.findByRequester_IdOrReceiver_Id(me.getId(), me.getId());
    }

    /** Persists an unread swap notification for the requester, attributing it to the supplied actor. */
    private void notifyRequester(TimetableSwapRequest request, User actor,
                                  Notification.Type type, String message) {
        notificationRepo.save(Notification.builder()
                .userId(request.getRequester().getUser().getId())
                .school(request.getSchool())
                .category(Notification.Category.TIMETABLE_SWAP)
                .targetType(Notification.TargetType.TIMETABLE_SWAP_REQUEST)
                .targetId(request.getId())
                .timetableSwapRequestId(request.getId())
                .type(type)
                .message(message)
                .isRead(false)
                .requester(actor)
                .build());
    }

    /** Persists an unread swap notification for the receiver, attributing it to the supplied actor. */
    private void notifyReceiver(TimetableSwapRequest request, User actor,
                                 Notification.Type type, String message) {
        notificationRepo.save(Notification.builder()
                .userId(request.getReceiver().getUser().getId())
                .school(request.getSchool())
                .category(Notification.Category.TIMETABLE_SWAP)
                .targetType(Notification.TargetType.TIMETABLE_SWAP_REQUEST)
                .targetId(request.getId())
                .timetableSwapRequestId(request.getId())
                .type(type)
                .message(message)
                .isRead(false)
                .requester(actor)
                .build());
    }
}
