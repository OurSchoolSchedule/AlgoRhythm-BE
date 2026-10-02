package com.rssolplan.edu.domain.schedule.generation;

import com.rssolplan.edu.domain.schedule.generation.dto.TimetableGenerationRequestDto;
import com.rssolplan.edu.domain.schedule.generation.dto.candidate.CandidateSchedule;
import com.rssolplan.edu.domain.schedule.generation.dto.candidate.ConfirmTimetableRequestDto;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableRequest;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableSet;
import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/api/timetable-generation")
@RequiredArgsConstructor
public class ScheduleGenerationController {

    private final ScheduleGenerationService service;

    /**
     * 1. 시간표 생성 요청 (교사 불가 교시 제출 요청)
     */
    @OwnerOnly
    @PostMapping("/requests")
    public ResponseEntity<TimetableRequest> requestTimetable(
            @AuthenticationPrincipal Long userId) {
        TimetableRequest request = service.requestTimetable(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(request);
    }

    /**
     * 2. 후보 시간표 생성
     */
    @OwnerOnly
    @PostMapping("/requests/{timetableRequestId}/generate")
    public ResponseEntity<Map<String, Object>> generateTimetable(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long timetableRequestId,
            @RequestBody TimetableGenerationRequestDto request) {
        Map<String, Object> response = service.generateTimetable(userId, timetableRequestId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 3. 후보 시간표 조회
     */
    @GetMapping("/candidates")
    public ResponseEntity<List<CandidateSchedule>> getCandidates(@RequestParam String key) {
        return ResponseEntity.ok(service.getCandidates(key));
    }

    /**
     * 4. 시간표 확정
     */
    @OwnerOnly
    @PostMapping("/requests/{timetableRequestId}/confirm")
    public ResponseEntity<?> confirmTimetable(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long timetableRequestId,
            @RequestBody ConfirmTimetableRequestDto dto) {
        TimetableSet timetableSet = service.confirmTimetable(userId, timetableRequestId, dto);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "시간표 확정 완료",
                "timetableSetId", timetableSet.getId()
        ));
    }

    /**
     * 미제출 교사 목록 조회
     */
    @OwnerOnly
    @GetMapping("/teachers/without-availability")
    public ResponseEntity<Map<String, Object>> getTeachersWithoutAvailability(
            @AuthenticationPrincipal Long userId) {
        List<Long> unsubmitted = service.getTeachersWithoutAvailability(userId);
        return ResponseEntity.ok(Map.of(
                "allSubmitted", unsubmitted.isEmpty(),
                "unsubmittedUserIds", unsubmitted
        ));
    }
}
