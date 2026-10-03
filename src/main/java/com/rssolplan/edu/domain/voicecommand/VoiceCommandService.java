package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.voicecommand.dto.*;
import com.rssolplan.edu.global.exception.DraftExpiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VoiceCommandService {

    private static final int DRAFT_TTL_MINUTES = 10;

    private final IntentParserService intentParserService;
    private final CommandPreviewService previewService;
    private final CommandExecutorService executorService;
    private final CommandDraftStore draftStore;

    /**
     * Parses the request text and returns a preview plus a draft token valid for ten minutes.
     * Stores the intent with its user ID without executing the command. The request's lang
     * field is unused. Preview lookup and draft-storage errors propagate.
     *
     * @throws com.rssolplan.edu.global.exception.IntentParseException if parsing fails, the intent is unknown,
     *         or required preview data is missing
     * @throws com.rssolplan.edu.global.exception.NotFoundException if a referenced preview resource is missing
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if required active-school access is missing
     * @throws IllegalStateException if draft storage fails
     */
    public CommandPreviewResponse preview(Long userId, VoiceCommandRequest request) {
        ParsedIntent intent = intentParserService.parse(request.text());

        // UNKNOWN이면 즉시 IntentParseException (GlobalExceptionHandler → 422)
        if (intent.resolvedType() == IntentType.UNKNOWN) {
            throw new com.rssolplan.edu.global.exception.IntentParseException(
                    "요청을 이해하지 못했습니다. 다시 말씀해 주세요.");
        }

        Map<String, Object> preview = previewService.buildPreview(userId, intent);

        DraftData draft = new DraftData(userId, intent);
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
     * Consumes a draft token, verifies its user ID, and executes the stored command,
     * returning the underlying service result. The token is consumed even when ownership
     * validation or execution fails. Execution and Redis access errors propagate.
     *
     * @throws DraftExpiredException if the token is missing, expired, or owned by another user
     * @throws IllegalStateException if the stored draft cannot be decoded
     */
    public Object confirm(Long userId, CommandConfirmRequest request) {
        DraftData draft = draftStore.getAndDelete(request.draftToken());

        // 토큰의 소유자와 현재 인증 사용자 비교
        if (!draft.userId().equals(userId)) {
            throw new DraftExpiredException("본인이 생성한 명령만 실행할 수 있습니다.");
        }

        return executorService.execute(userId, draft.intent());
    }

    /** Returns a Korean summary of the proposed operation, with a generic summary for an unknown intent. */
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
            case ATTENDANCE_CHECK_IN -> "출근 처리를 진행합니다.";
            case ATTENDANCE_CHECK_OUT -> "퇴근 처리를 진행합니다.";
            default -> "명령을 실행합니다.";
        };
    }

    /**
     * Returns the Korean label for ACCEPT, REJECT, or APPROVE regardless of case;
     * returns an empty string for null and preserves other action text.
     */
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
