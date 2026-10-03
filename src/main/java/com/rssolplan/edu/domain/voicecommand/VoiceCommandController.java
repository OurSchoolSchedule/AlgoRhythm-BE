package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.auth.dto.ApiResponse;
import com.rssolplan.edu.domain.voicecommand.dto.CommandConfirmRequest;
import com.rssolplan.edu.domain.voicecommand.dto.CommandPreviewResponse;
import com.rssolplan.edu.domain.voicecommand.dto.VoiceCommandRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/voice-commands")
@RequiredArgsConstructor
public class VoiceCommandController {

    private final VoiceCommandService voiceCommandService;

    /**
     * 1단계: 자연어 입력 → AI 파싱 → 미리보기 + draftToken 발급
     * 422 응답: 의도 파싱 실패 (IntentParseException)
     */
    @PostMapping("/preview")
    public ResponseEntity<ApiResponse<CommandPreviewResponse>> preview(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid VoiceCommandRequest request) {
        CommandPreviewResponse response = voiceCommandService.preview(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * 2단계: draftToken 확인 → 기존 Service 실행 → 결과 반환
     * 401 응답: 토큰 만료 또는 userId 불일치 (DraftExpiredException)
     */
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<Object>> confirm(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid CommandConfirmRequest request) {
        Object result = voiceCommandService.confirm(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}
