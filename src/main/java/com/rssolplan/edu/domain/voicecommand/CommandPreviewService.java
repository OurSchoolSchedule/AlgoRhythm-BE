package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.schedule.substitute.SubstituteRequestRepository;
import com.rssolplan.edu.domain.schedule.substitute.SubstituteResponseRepository;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteResponse;
import com.rssolplan.edu.domain.schedule.generation.TimetableRepository;
import com.rssolplan.edu.domain.schedule.generation.TimetableSetRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableSet;
import com.rssolplan.edu.domain.schedule.operations.LegacyApprovalGuard;
import com.rssolplan.edu.domain.schedule.operations.ScheduleAccess;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.voicecommand.dto.IntentType;
import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;
import com.rssolplan.edu.domain.voicecommand.dto.VoiceCommandRequest;
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
    private final TimetableSetRepository terms;

    public long requireActiveSchool(long userId, Long expectedSchool) {
        long school = access.member(userId, false).getSchool().getId();
        if (expectedSchool != null && school != expectedSchool)
            throw new NotFoundException("현재 선택한 학교를 확인하세요.");
        return school;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildPreview(Long userId, ParsedIntent intent, long expectedSchool, VoiceCommandRequest request) {
        IntentType type = intent.resolvedType();
        boolean requiresAdmin = type == IntentType.SUBSTITUTE_CREATE || type == IntentType.SUBSTITUTE_APPROVE;
        SchoolUser member = access.member(userId, requiresAdmin);
        Long schoolId = member.getSchool().getId();
        if (schoolId != expectedSchool) throw new NotFoundException("현재 선택한 학교를 확인하세요.");
        TimetableSet term = request.termId() == null ? null : terms.findByIdAndSchool_Id(request.termId(), schoolId)
                .orElseThrow(() -> new NotFoundException("학기를 찾을 수 없습니다."));
        return switch (type) {
            case SUBSTITUTE_CREATE    -> previewSubstituteCreate(schoolId, term, intent);
            case SUBSTITUTE_RESPOND   -> previewSubstituteRespond(member, term, intent);
            case SUBSTITUTE_APPROVE   -> previewSubstituteApprove(schoolId, term, intent);
            case AVAILABILITY_ADD,
                 AVAILABILITY_REPLACE -> previewAvailability(intent);
            case GUIDE -> Map.of("schoolId", schoolId, "userId", userId);
            case MY_WEEK, COVER_OPTIONS, SWAP_OPTIONS, CHANGE_STATUS -> {
                if (member.getPosition() != SchoolUser.Position.TEACHER)
                    throw new ForbiddenException("재직 교사 계정에서 내 수업을 선택하세요.");
                Map<String, Object> filters = new HashMap<>();
                if (term != null) filters.put("termId", term.getId());
                if (type != IntentType.CHANGE_STATUS)
                    putDate(filters, false, intent.substituteDate(), intent.weekStart(), request.requestDate(), term);
                if (type == IntentType.SWAP_OPTIONS)
                    putDate(filters, true, intent.targetDate(), intent.targetWeekStart(), request.targetDate(), term);
                if (type == IntentType.MY_WEEK)
                    filters.put("range", LessonDates.isWeekQuery(request.text(), intent) ? "WEEK" : "DAY");
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

    private static void putDate(Map<String, Object> filters, boolean target, String mentioned, String weekStart,
                                String selection, TimetableSet term) {
        LocalDate selected = LessonDates.parse(selection);
        LocalDate date = mentioned == null ? selected : LessonDates.parse(mentioned);
        LocalDate week = LessonDates.parse(weekStart);
        String field = target ? "targetDate" : "date";
        if (week != null) filters.put(target ? "targetWeekStart" : "weekStart", weekStart);
        if (date == null) return;
        filters.put(field, date.toString());
        if (mentioned != null) filters.put(field + "FromText", true);
        if (selected != null && (week == null ? !date.equals(selected) :
                selected.isBefore(week) || selected.isAfter(week.plusDays(6))))
            filters.put(field + "Conflict", true);
        if (term != null) {
            if (date.isBefore(term.getStartDate()) || date.isAfter(term.getEndDate()))
                filters.put(field + "Status", "OUTSIDE_TERM");
            else if (term.getExcludedDates().contains(date)) filters.put(field + "Status", "EXCLUDED_DATE");
        }
    }

    private static void checkTerm(TimetableSet term, Timetable lesson) {
        if (term != null && (lesson.getAcademicYear() != term.getAcademicYear() || lesson.getSemester() != term.getSemester()))
            throw new NotFoundException("선택한 업무 학기의 수업이 아닙니다.");
    }

    private Map<String, Object> previewSubstituteCreate(Long schoolId, TimetableSet term, ParsedIntent intent) {
        if (intent.timetableId() == null) {
            throw new IntentParseException("시간표 ID를 인식하지 못했습니다. 다시 말씀해 주세요.");
        }

        Timetable timetable = timetableRepository.findById(intent.timetableId())
                .filter(t -> schoolId.equals(t.getSchool().getId()))
                .orElseThrow(() -> new NotFoundException("해당 시간표를 찾을 수 없습니다."));
        checkTerm(term, timetable);

        LocalDate date;
        try {
            date = LocalDate.parse(intent.substituteDate() == null ? "" : intent.substituteDate());
        } catch (java.time.DateTimeException invalid) {
            throw new IntentParseException("보결 날짜를 다시 입력하세요.");
        }
        if (term != null && (date.isBefore(term.getStartDate()) || date.isAfter(term.getEndDate())))
            throw new IntentParseException("요청 날짜가 선택한 학기 기간 밖입니다.");
        if (term != null && term.getExcludedDates().contains(date))
            throw new IntentParseException("시험·휴업 등으로 정규 수업에서 제외한 날짜입니다.");
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

    private Map<String, Object> previewSubstituteRespond(SchoolUser member, TimetableSet term, ParsedIntent intent) {
        if (intent.requestId() == null) {
            throw new IntentParseException("보결 요청 ID를 인식하지 못했습니다.");
        }
        SubstituteRequest req = substituteRequestRepository.findById(intent.requestId())
                .filter(r -> r.getSchool().getId().equals(member.getSchool().getId()))
                .orElseThrow(() -> new NotFoundException("보결 요청을 찾을 수 없습니다."));

        if (!req.includesCandidate(member.getId()) && !req.includesCandidate(access.teachingId(member))) {
            throw new ForbiddenException("해당 보결 요청에 응답할 권한이 없습니다.");
        }
        checkTerm(term, req.getTimetable());
        return Map.of(
                "requestId", req.getId(),
                "timetableInfo", buildTimetableInfo(req.getTimetable()),
                "substituteDate", req.getSubstituteDate(),
                "currentStatus", req.getStatus().name(),
                "action", intent.action() != null ? intent.action() : "(미확인)"
        );
    }

    private Map<String, Object> previewSubstituteApprove(Long schoolId, TimetableSet term, ParsedIntent intent) {
        if (intent.responseId() == null) {
            throw new IntentParseException("교사 응답 ID를 인식하지 못했습니다.");
        }
        SubstituteResponse resp = substituteResponseRepository.findById(intent.responseId())
                .filter(r -> schoolId.equals(r.getSubstituteRequest().getSchool().getId()))
                .orElseThrow(() -> new NotFoundException("교사 응답 데이터를 찾을 수 없습니다."));

        SubstituteRequest req = resp.getSubstituteRequest();
        checkTerm(term, req.getTimetable());
        return Map.of(
                "responseId", resp.getId(),
                "teacherName", resp.getCandidate().getSchoolDisplayName(),
                "teacherAction", resp.getWorkerAction().name(),
                "timetableInfo", buildTimetableInfo(req.getTimetable()),
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

    private String buildTimetableInfo(Timetable t) {
        return String.format("%s %s | %s | %s교시 | 담당: %s",
                t.getSchoolClass().getGrade() + "학년 " + t.getSchoolClass().getClassNumber() + "반",
                t.getDayOfWeek().name(),
                t.getSubject().getName(),
                t.getPeriodSetting().getPeriodNumber(),
                t.getTeacher().getSchoolDisplayName());
    }
}
