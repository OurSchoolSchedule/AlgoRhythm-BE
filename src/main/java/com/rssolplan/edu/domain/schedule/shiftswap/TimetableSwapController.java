package com.rssolplan.edu.domain.schedule.shiftswap;

import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/timetable-swap")
@RequiredArgsConstructor
public class TimetableSwapController {

    private final TimetableSwapService timetableSwapService;

    @PostMapping("/requests")
    public ResponseEntity<TimetableSwapResponseDto> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody CreateSwapRequestDto dto) {
        TimetableSwapRequest request = timetableSwapService.create(
                userId,
                dto.getRequesterTimetableId(), dto.getRequesterDate(),
                dto.getReceiverTimetableId(), dto.getReceiverDate(),
                dto.getReason());
        return ResponseEntity.status(HttpStatus.CREATED).body(TimetableSwapResponseDto.from(request));
    }

    @PostMapping("/requests/{requestId}/respond")
    public ResponseEntity<TimetableSwapResponseDto> respond(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long requestId,
            @RequestBody ActionDto dto) {
        return ResponseEntity.ok(
                TimetableSwapResponseDto.from(timetableSwapService.respond(userId, requestId, dto.getAction())));
    }

    @OwnerOnly
    @PostMapping("/requests/{requestId}/approve")
    public ResponseEntity<TimetableSwapResponseDto> managerApproval(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long requestId,
            @RequestBody ActionDto dto) {
        return ResponseEntity.ok(
                TimetableSwapResponseDto.from(timetableSwapService.managerApproval(userId, requestId, dto.getAction())));
    }

    @GetMapping("/requests/me")
    public ResponseEntity<List<TimetableSwapResponseDto>> getMyRequests(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(
                timetableSwapService.getMyRequests(userId).stream()
                        .map(TimetableSwapResponseDto::from)
                        .collect(java.util.stream.Collectors.toList()));
    }

    @Getter
    @NoArgsConstructor
    public static class CreateSwapRequestDto {
        private Long requesterTimetableId;
        private LocalDate requesterDate;
        private Long receiverTimetableId;
        private LocalDate receiverDate;
        private String reason;
    }

    @Getter
    @NoArgsConstructor
    public static class ActionDto {
        private String action;
    }
}
