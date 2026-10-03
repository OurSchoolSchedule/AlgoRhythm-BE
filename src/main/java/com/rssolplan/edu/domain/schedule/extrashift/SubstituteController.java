package com.rssolplan.edu.domain.schedule.extrashift;

import com.rssolplan.edu.domain.schedule.extrashift.dto.*;
import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/substitutes")
@RequiredArgsConstructor
public class SubstituteController {

    private final SubstituteService substituteService;

    // 교감/교장: 보결 요청 생성
    @OwnerOnly
    @PostMapping("/requests")
    public ResponseEntity<SubstituteRequestDetail> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid SubstituteCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(substituteService.create(userId, request));
    }

    // 교사: 보결 요청 수락/거절
    @PatchMapping("/requests/{requestId}/response")
    public ResponseEntity<SubstituteResponseDetail> respond(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long requestId,
            @RequestBody @Valid SubstituteRespondRequest request) {
        return ResponseEntity.ok(substituteService.respond(userId, requestId, request));
    }

    // 교감/교장: 교사 응답 최종 승인/거절
    @OwnerOnly
    @PatchMapping("/responses/{responseId}/approval")
    public ResponseEntity<SubstituteApprovalDetail> approve(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long responseId,
            @RequestBody @Valid SubstituteApprovalRequest request) {
        return ResponseEntity.ok(substituteService.approve(userId, responseId, request));
    }

    // 교사: 현재 학교의 보결 요청 목록 조회
    @GetMapping("/requests")
    public ResponseEntity<List<SubstituteRequestDetail>> getRequests(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "OPEN") String status) {
        return ResponseEntity.ok(substituteService.getRequests(userId, status));
    }
}
