package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.schedule.substitute.SubstituteRequestRepository;
import com.rssolplan.edu.domain.schedule.substitute.SubstituteResponseRepository;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteResponse;
import com.rssolplan.edu.domain.schedule.generation.TimetableRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.schedule.operations.LegacyApprovalGuard;
import com.rssolplan.edu.domain.schedule.operations.ScheduleAccess;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.voicecommand.dto.IntentType;
import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;
import com.rssolplan.edu.global.exception.IntentParseException;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CommandPreviewService {

    private final TimetableRepository timetableRepository;
    private final SubstituteRequestRepository substituteRequestRepository;
    private final SubstituteResponseRepository substituteResponseRepository;
    private final SchoolUserRepository schoolUserRepository;
    private final ScheduleAccess access;
    private final LegacyApprovalGuard guard;

    public void requireActiveMember(long userId) {
        access.member(userId, false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildPreview(Long userId, ParsedIntent intent) {
        IntentType type = intent.resolvedType();
        boolean requiresAdmin = type == IntentType.SUBSTITUTE_CREATE || type == IntentType.SUBSTITUTE_APPROVE;
        SchoolUser member = access.member(userId, requiresAdmin);
        Long schoolId = member.getSchool().getId();
        return switch (type) {
            case SUBSTITUTE_CREATE    -> previewSubstituteCreate(schoolId, intent);
            case SUBSTITUTE_RESPOND   -> previewSubstituteRespond(member, intent);
            case SUBSTITUTE_APPROVE   -> previewSubstituteApprove(schoolId, intent);
            case AVAILABILITY_ADD,
                 AVAILABILITY_REPLACE -> previewAvailability(intent);
            case MY_WEEK, COVER_OPTIONS, SWAP_OPTIONS, CHANGE_STATUS -> {
                if (member.getPosition() != SchoolUser.Position.TEACHER)
                    throw new ForbiddenException("재직 교사 계정에서 내 수업을 선택하세요.");
                Map<String, Object> filters = new HashMap<>();
                if (intent.substituteDate() != null) {
                    try { filters.put("date", java.time.LocalDate.parse(intent.substituteDate()).toString()); }
                    catch (java.time.DateTimeException invalid) { throw new IntentParseException("날짜를 다시 입력하세요."); }
                }
                if (intent.periodNumber() != null) {
                    if (intent.periodNumber() < 1 || intent.periodNumber() > 99)
                        throw new IntentParseException("교시 번호를 다시 입력하세요.");
                    filters.put("periodNumber", intent.periodNumber());
                }
                if (intent.teacherName() != null) filters.put("teacherName", intent.teacherName());
                filters.put("schoolId", schoolId);
                filters.put("userId", userId);
                yield filters;
            }
            case UNKNOWN              -> throw new IntentParseException("요청을 이해하지 못했습니다.");
        };
    }

    private Map<String, Object> previewSubstituteCreate(Long schoolId, ParsedIntent intent) {
        if (intent.timetableId() == null) {
            throw new IntentParseException("시간표 ID를 인식하지 못했습니다. 다시 말씀해 주세요.");
        }

        Timetable timetable = timetableRepository.findById(intent.timetableId())
                .filter(t -> schoolId.equals(t.getSchool().getId()))
                .orElseThrow(() -> new NotFoundException("해당 시간표를 찾을 수 없습니다."));

        LocalDate date;
        try {
            date = LocalDate.parse(intent.substituteDate() == null ? "" : intent.substituteDate());
        } catch (java.time.DateTimeException invalid) {
            throw new IntentParseException("보결 날짜를 다시 입력하세요.");
        }
        var eligible = guard.coverCandidates(new LegacyApprovalGuard.Target(timetable, date), null);
        var candidates = schoolUserRepository.findAllById(eligible);

        Map<String, Object> preview = new HashMap<>();
        preview.put("timetableId", timetable.getId());
        preview.put("timetableInfo", buildTimetableInfo(timetable));
        preview.put("substituteDate", intent.substituteDate());
        preview.put("note", intent.note());
        preview.put("candidateTeachers", candidates.stream().map(c -> Map.of(
                "schoolUserId", c.getId(),
                "name", c.getSchoolDisplayName()
        )).toList());
        return preview;
    }

    private Map<String, Object> previewSubstituteRespond(SchoolUser member, ParsedIntent intent) {
        if (intent.requestId() == null) {
            throw new IntentParseException("보결 요청 ID를 인식하지 못했습니다.");
        }
        SubstituteRequest req = substituteRequestRepository.findById(intent.requestId())
                .filter(r -> r.getSchool().getId().equals(member.getSchool().getId()))
                .orElseThrow(() -> new NotFoundException("보결 요청을 찾을 수 없습니다."));

        if (!req.includesCandidate(member.getId()) && !req.includesCandidate(access.teachingId(member))) {
            throw new ForbiddenException("해당 보결 요청에 응답할 권한이 없습니다.");
        }
        return Map.of(
                "requestId", req.getId(),
                "timetableInfo", coverLessonInfo(req),
                "substituteDate", req.getSubstituteDate(),
                "currentStatus", req.getStatus().name(),
                "action", intent.action() != null ? intent.action() : "(미확인)"
        );
    }

    private Map<String, Object> previewSubstituteApprove(Long schoolId, ParsedIntent intent) {
        if (intent.responseId() == null) {
            throw new IntentParseException("교사 응답 ID를 인식하지 못했습니다.");
        }
        SubstituteResponse resp = substituteResponseRepository.findById(intent.responseId())
                .filter(r -> schoolId.equals(r.getSubstituteRequest().getSchool().getId()))
                .orElseThrow(() -> new NotFoundException("교사 응답 데이터를 찾을 수 없습니다."));

        SubstituteRequest req = resp.getSubstituteRequest();
        return Map.of(
                "responseId", resp.getId(),
                "teacherName", resp.getCandidate().getSchoolDisplayName(),
                "teacherAction", resp.getWorkerAction().name(),
                "timetableInfo", coverLessonInfo(req),
                "substituteDate", req.getSubstituteDate(),
                "action", intent.action() != null ? intent.action() : "(미확인)"
        );
    }

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

    private String coverLessonInfo(SubstituteRequest request) {
        return request.getDatedLessonId() == null ? buildTimetableInfo(request.getTimetable())
                : String.format("%s %d교시 · 날짜 수업 #%d", request.getSubstituteDate(), request.periodNumber(), request.getDatedLessonId());
    }

    private String buildTimetableInfo(Timetable t) {
        return String.format("%s %s | %s | %s교시 | 담당: %s",
                t.getSchoolClass().getGrade() + "학년 " + t.getSchoolClass().getClassNumber() + "반",
                t.getDayOfWeek().name(),
                t.getSubject().getName(),
                t.getPeriodSetting().getPeriodNumber(),
                t.getTeacher().getSchoolDisplayName());
    }
}
