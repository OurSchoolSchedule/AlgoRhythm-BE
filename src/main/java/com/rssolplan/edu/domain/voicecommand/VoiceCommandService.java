package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.voicecommand.dto.*;
import com.rssolplan.edu.global.exception.DraftExpiredException;
import com.rssolplan.edu.global.exception.IntentParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class VoiceCommandService {

    private static final int DRAFT_TTL_MINUTES = 10;

    private final IntentParserService intentParserService;
    private final CommandPreviewService previewService;
    private final CommandExecutorService executorService;
    private final CommandDraftStore draftStore;

    /**
     * 1단계: 자연어 → Intent 파싱 → Preview 생성 → draftToken 발급
     */
    public CommandPreviewResponse preview(Long userId, VoiceCommandRequest request) {
        long school = previewService.requireActiveSchool(userId, request.schoolId());
        ParsedIntent intent = intentParserService.parse(request.text());

        // UNKNOWN이면 즉시 IntentParseException (GlobalExceptionHandler → 422)
        if (intent.resolvedType() == IntentType.UNKNOWN) {
            throw new IntentParseException(
                    "요청을 이해하지 못했습니다. 다시 말씀해 주세요.");
        }

        if (intent.resolvedType() == IntentType.SUBSTITUTE_CREATE) {
            String date = intent.substituteDate();
            if (date == null && request.requestDate() != null) {
                var selected = LessonDates.parse(request.requestDate());
                var week = LessonDates.parse(intent.weekStart());
                if (week != null && (selected.isBefore(week) || selected.isAfter(week.plusDays(6))))
                    throw new IntentParseException("요청한 주간에 포함되는 수업 날짜를 선택하세요.");
                date = selected.toString();
                intent = intent.withDates(date, null, null, null);
            }
            ensureSameDate(date, request.requestDate());
        }
        Map<String, Object> preview = previewService.buildPreview(userId, intent, school, request);

        // A lesson intent only opens a live picker. The v2 lesson endpoint authorizes the
        // selected lessons and recipients again after the teacher reviews the request.
        if (intent.resolvedType().isLessonAssistant())
            return new CommandPreviewResponse(null, intent.intentType(), intent.confidence(),
                    buildDescription(intent), preview, null);

        if (preview.get("substituteDate") != null) {
            String recorded = preview.get("substituteDate").toString();
            ensureSameDate(recorded, intent.substituteDate());
            ensureSameDate(recorded, request.requestDate());
        }
        DraftData draft = new DraftData(userId, intent, school, request.termId(), request.requestDate(), request.targetDate());
        String token = draftStore.save(draft);

        return new CommandPreviewResponse(
                token,
                intent.intentType(),
                intent.confidence(),
                buildDescription(intent),
                preview,
                LocalDateTime.now().plusMinutes(DRAFT_TTL_MINUTES)
        );
    }

    /**
     * 2단계: draftToken 검증 → userId 일치 확인 → 기존 Service Write 실행
     */
    @Transactional
    public Object confirm(Long userId, CommandConfirmRequest request) {
        DraftData draft = draftStore.getAndDelete(request.draftToken());

        // 토큰의 소유자와 현재 인증 사용자 비교
        if (!draft.userId().equals(userId)) {
            throw new DraftExpiredException("본인이 생성한 명령만 실행할 수 있습니다.");
        }
        // Drafts issued by older releases did not record which school's data the user reviewed.
        if (draft.schoolId() == null || request.schoolId() != null && !draft.schoolId().equals(request.schoolId())
                || !Objects.equals(draft.termId(), request.termId())
                || !Objects.equals(draft.requestDate(), request.requestDate())
                || !Objects.equals(draft.targetDate(), request.targetDate()))
            throw new DraftExpiredException("업무 학교·학기 또는 날짜가 변경되었습니다. 현재 조건으로 다시 확인하세요.");
        previewService.buildPreview(userId, draft.intent(), draft.schoolId(),
                new VoiceCommandRequest("", null, draft.schoolId(), draft.termId(), draft.requestDate(), draft.targetDate()));
        return executorService.execute(userId, draft.intent());
    }

    private static void ensureSameDate(String recorded, String requested) {
        if (recorded != null && requested != null && !recorded.equals(LessonDates.parse(requested).toString()))
            throw new IntentParseException("선택한 날짜와 요청의 수업 날짜가 다릅니다. 날짜를 확인한 후 다시 요청하세요.");
    }

    private String buildDescription(ParsedIntent intent) {
        return switch (intent.resolvedType()) {
            case SUBSTITUTE_CREATE ->
                    String.format("시간표 #%d의 %s 보결 교사 요청을 생성합니다.",
                            intent.timetableId(), intent.substituteDate());
            case SUBSTITUTE_RESPOND ->
                    String.format("보결 요청 #%d에 %s 합니다.",
                            intent.requestId(), toKorean(intent.action()));
            case SUBSTITUTE_APPROVE ->
                    String.format("교사 응답 #%d을 %s 합니다.",
                            intent.responseId(), toKorean(intent.action()));
            case AVAILABILITY_ADD ->
                    String.format("불가 교시 %d개를 추가 등록합니다.",
                            intent.unavailabilitySlots() != null ? intent.unavailabilitySlots().size() : 0);
            case AVAILABILITY_REPLACE ->
                    String.format("불가 교시를 %d개로 전체 교체합니다.",
                            intent.unavailabilitySlots() != null ? intent.unavailabilitySlots().size() : 0);
            case GUIDE -> "내 시간표를 조회하거나, 내 수업에 보결·교환을 요청할 수 있습니다. 필요한 수업과 상대를 고른 뒤 요청 내용을 확인하여 보냅니다. 보낸 요청의 처리 상태도 조회할 수 있습니다.";
            case MY_WEEK -> "요청한 날짜 또는 주간의 내 시간표입니다.";
            case COVER_OPTIONS -> "본인 수업을 선택해 공강 교사를 확인하세요.";
            case SWAP_OPTIONS -> "본인 수업과 상대 교사의 교환 가능 수업을 선택하세요.";
            case CHANGE_STATUS -> "내가 보낸 요청의 현재 처리 상태입니다.";
            default -> "명령을 실행합니다.";
        };
    }

    private String toKorean(String action) {
        if (action == null) return "";
        return switch (action.toUpperCase()) {
            case "ACCEPT" -> "수락";
            case "REJECT" -> "거절";
            case "APPROVE" -> "승인";
            default -> action;
        };
    }
}
