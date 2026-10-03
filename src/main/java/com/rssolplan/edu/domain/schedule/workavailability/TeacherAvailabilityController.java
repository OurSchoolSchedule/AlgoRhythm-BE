package com.rssolplan.edu.domain.schedule.workavailability;

import com.rssolplan.edu.domain.schedule.workavailability.dto.TeacherAvailabilityRequestDto;
import com.rssolplan.edu.domain.schedule.workavailability.dto.TeacherAvailabilityResponseDto;
import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TeacherAvailabilityController {

    private final TeacherAvailabilityService teacherAvailabilityService;

    /** 내 불가 교시 조회 */
    @GetMapping("/me/unavailabilities")
    public ResponseEntity<List<TeacherAvailabilityResponseDto>> getMyUnavailabilities(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(teacherAvailabilityService.getMyUnavailabilities(userId));
    }

    /** 불가 교시 등록 (기존 유지) */
    @PostMapping("/me/unavailabilities")
    public ResponseEntity<List<TeacherAvailabilityResponseDto>> addUnavailabilities(
            @AuthenticationPrincipal Long userId,
            @RequestBody TeacherAvailabilityRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(teacherAvailabilityService.addUnavailabilities(userId, request));
    }

    /** 불가 교시 전체 교체 */
    @PutMapping("/me/unavailabilities")
    public ResponseEntity<List<TeacherAvailabilityResponseDto>> replaceUnavailabilities(
            @AuthenticationPrincipal Long userId,
            @RequestBody TeacherAvailabilityRequestDto request) {
        return ResponseEntity.ok(teacherAvailabilityService.replaceUnavailabilities(userId, request));
    }

    /** 특정 불가 교시 삭제 */
    @DeleteMapping("/me/unavailabilities/{availabilityId}")
    public ResponseEntity<Void> deleteUnavailability(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long availabilityId) {
        teacherAvailabilityService.deleteUnavailability(userId, availabilityId);
        return ResponseEntity.noContent().build();
    }

    /** 학교 전체 교사 불가 교시 조회 (ADMIN용) */
    @OwnerOnly
    @GetMapping("/school/unavailabilities")
    public ResponseEntity<List<TeacherAvailabilityResponseDto>> getAllUnavailabilities(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(teacherAvailabilityService.getAllUnavailabilities(userId));
    }
}
