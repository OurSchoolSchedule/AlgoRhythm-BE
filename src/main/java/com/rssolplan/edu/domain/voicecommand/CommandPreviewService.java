package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.schedule.attendance.TeacherAttendanceService;
import com.rssolplan.edu.domain.schedule.extrashift.SubstituteRequestRepository;
import com.rssolplan.edu.domain.schedule.extrashift.SubstituteResponseRepository;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteResponse;
import com.rssolplan.edu.domain.schedule.generation.TimetableRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.voicecommand.dto.IntentType;
import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;
import com.rssolplan.edu.global.exception.IntentParseException;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommandPreviewService {

    private final TimetableRepository timetableRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteResponseRepository substituteResponseRepository;
    private final SchoolUserRepository schoolUserRepository;
    private final TeacherAttendanceService attendanceService;
    private final AuthorizationService authService;

    /**
     * Returns display data for the proposed command without executing it. Resource lookups
     * and attendance authorization errors propagate; a successful preview does not establish
     * that command execution is authorized or that its state preconditions hold.
     *
     * @throws IntentParseException if the intent is unknown or required preview parameters are missing
     * @throws NotFoundException if a referenced resource or user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if required active-school access is missing
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildPreview(Long userId, ParsedIntent intent) {
        return switch (intent.resolvedType()) {
            case SUBSTITUTE_CREATE    -> previewSubstituteCreate(userId, intent);
            case SUBSTITUTE_RESPOND   -> previewSubstituteRespond(intent);
            case SUBSTITUTE_APPROVE   -> previewSubstituteApprove(intent);
            case AVAILABILITY_ADD,
                 AVAILABILITY_REPLACE -> previewAvailability(intent);
            case ATTENDANCE_CHECK_IN  -> previewAttendance(userId, "출근");
            case ATTENDANCE_CHECK_OUT -> previewAttendance(userId, "퇴근");
            case UNKNOWN              -> throw new IntentParseException("요청을 이해하지 못했습니다.");
        };
    }

    /**
     * Returns timetable details and prospective teachers from the user's active school,
     * excluding the timetable's current teacher. Date and note may be null.
     *
     * @throws IntentParseException if the timetable ID is missing
     * @throws NotFoundException if the timetable or user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
    private Map<String, Object> previewSubstituteCreate(Long userId, ParsedIntent intent) {
        if (intent.timetableId() == null) {
            throw new IntentParseException("시간표 ID를 인식하지 못했습니다. 다시 말씀해 주세요.");
        }

        Timetable timetable = timetableRepository.findById(intent.timetableId())
                .orElseThrow(() -> new NotFoundException("해당 시간표를 찾을 수 없습니다."));

        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        List<SchoolUser> candidates = schoolUserRepository
                .findBySchool_IdAndPosition(schoolId, SchoolUser.Position.TEACHER)
                .stream()
                .filter(t -> !t.getId().equals(timetable.getTeacher().getId()))
                .toList();

        Map<String, Object> preview = new HashMap<>();
        preview.put("timetableId", timetable.getId());
        preview.put("timetableInfo", buildTimetableInfo(timetable));
        preview.put("substituteDate", intent.substituteDate());
        preview.put("note", intent.note());
        preview.put("candidateTeachers", candidates.stream().map(c -> Map.of(
                "schoolUserId", c.getId(),
                "name", c.getUser().getUsername()
        )).toList());
        return preview;
    }

    /**
     * Returns the request's timetable, date, status, and proposed action for display.
     * Uses a placeholder when the action is null.
     *
     * @throws IntentParseException if the request ID is missing
     * @throws NotFoundException if the request does not exist
     */
    private Map<String, Object> previewSubstituteRespond(ParsedIntent intent) {
        if (intent.requestId() == null) {
            throw new IntentParseException("보결 요청 ID를 인식하지 못했습니다.");
        }
        SubstituteRequest req = substituteRequestRepository.findById(intent.requestId())
                .orElseThrow(() -> new NotFoundException("보결 요청을 찾을 수 없습니다."));

        return Map.of(
                "requestId", req.getId(),
                "timetableInfo", buildTimetableInfo(req.getTimetable()),
                "substituteDate", req.getSubstituteDate(),
                "currentStatus", req.getStatus().name(),
                "action", intent.action() != null ? intent.action() : "(미확인)"
        );
    }

    /**
     * Returns the responding teacher, recorded action, timetable, date, and proposed
     * approval action for display. Uses a placeholder when the action is null.
     *
     * @throws IntentParseException if the response ID is missing
     * @throws NotFoundException if the response does not exist
     */
    private Map<String, Object> previewSubstituteApprove(ParsedIntent intent) {
        if (intent.responseId() == null) {
            throw new IntentParseException("교사 응답 ID를 인식하지 못했습니다.");
        }
        SubstituteResponse resp = substituteResponseRepository.findById(intent.responseId())
                .orElseThrow(() -> new NotFoundException("교사 응답 데이터를 찾을 수 없습니다."));

        SubstituteRequest req = resp.getSubstituteRequest();
        return Map.of(
                "responseId", resp.getId(),
                "teacherName", resp.getCandidate().getUser().getUsername(),
                "teacherAction", resp.getWorkerAction().name(),
                "timetableInfo", buildTimetableInfo(req.getTimetable()),
                "substituteDate", req.getSubstituteDate(),
                "action", intent.action() != null ? intent.action() : "(미확인)"
        );
    }

    /**
     * Returns the add-or-replace label and proposed unavailable periods for display,
     * converting null reasons to empty strings.
     *
     * @throws IntentParseException if the slot list is null or empty
     */
    private Map<String, Object> previewAvailability(ParsedIntent intent) {
        if (intent.unavailabilitySlots() == null || intent.unavailabilitySlots().isEmpty()) {
            throw new IntentParseException("불가 교시 정보를 인식하지 못했습니다.");
        }
        boolean isReplace = intent.resolvedType() == IntentType.AVAILABILITY_REPLACE;
        return Map.of(
                "operation", isReplace ? "전체 교체" : "추가 등록",
                "slots", intent.unavailabilitySlots().stream().map(slot -> Map.of(
                        "dayOfWeek", slot.dayOfWeek(),
                        "periodNumber", slot.periodNumber(),
                        "reason", slot.reason() != null ? slot.reason() : ""
                )).toList()
        );
    }

    /**
     * Returns the proposed action and today's attendance status and date.
     * Attendance lookup and authorization errors propagate.
     */
    private Map<String, Object> previewAttendance(Long userId, String action) {
        var today = attendanceService.getTodayAttendance(userId);
        return Map.of(
                "action", action + " 처리",
                "currentStatus", today.status() != null ? today.status() : "BEFORE_WORK",
                "workDate", today.workDate()
        );
    }

    /** Formats the class, weekday, subject, period number, and teacher as a Korean display summary. */
    private String buildTimetableInfo(Timetable t) {
        return String.format("%s %s | %s | %s교시 | 담당: %s",
                t.getSchoolClass().getGrade() + "학년 " + t.getSchoolClass().getClassNumber() + "반",
                t.getDayOfWeek().name(),
                t.getSubject().getName(),
                t.getPeriodSetting().getPeriodNumber(),
                t.getTeacher().getUser().getUsername());
    }
}
