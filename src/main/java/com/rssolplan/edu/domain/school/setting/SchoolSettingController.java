package com.rssolplan.edu.domain.school.setting;

import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/school-setting")
@RequiredArgsConstructor
public class SchoolSettingController {

    private final SchoolSettingService settingService;

    /** 학교 설정 조회 */
    @GetMapping
    public ResponseEntity<SchoolSettingResponse> getSetting(
            @AuthenticationPrincipal Long userId) {
        SchoolSetting setting = settingService.getSetting(userId);
        return ResponseEntity.ok(new SchoolSettingResponse(setting));
    }

    /** 학교 기본 설정 생성/수정 (ADMIN) */
    @OwnerOnly
    @PostMapping
    public ResponseEntity<SchoolSettingResponse> createOrUpdateSetting(
            @AuthenticationPrincipal Long userId,
            @RequestBody SchoolSettingRequest req) {
        SchoolSetting setting = settingService.createOrUpdateSetting(
                userId, req.getPeriodDuration(), req.getBreakDuration(),
                req.getLunchStartTime(), req.getLunchEndTime());
        return ResponseEntity.ok(new SchoolSettingResponse(setting));
    }

    /** 교시 목록 조회 */
    @GetMapping("/periods")
    public ResponseEntity<List<PeriodSettingResponse>> getPeriods(
            @AuthenticationPrincipal Long userId) {
        List<PeriodSetting> periods = settingService.getPeriods(userId);
        List<PeriodSettingResponse> responses = periods.stream()
                .map(PeriodSettingResponse::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /** 교시 추가 (ADMIN) */
    @OwnerOnly
    @PostMapping("/periods")
    public ResponseEntity<PeriodSettingResponse> addPeriod(
            @AuthenticationPrincipal Long userId,
            @RequestBody PeriodSettingRequest req) {
        PeriodSetting period = settingService.addPeriod(
                userId, req.getPeriodNumber(), req.getStartTime(), req.getEndTime());
        return ResponseEntity.ok(new PeriodSettingResponse(period));
    }

    /** 교시 수정 (ADMIN) */
    @OwnerOnly
    @PutMapping("/periods/{periodId}")
    public ResponseEntity<PeriodSettingResponse> updatePeriod(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long periodId,
            @RequestBody PeriodSettingRequest req) {
        PeriodSetting period = settingService.updatePeriod(
                userId, periodId, req.getStartTime(), req.getEndTime());
        return ResponseEntity.ok(new PeriodSettingResponse(period));
    }

    /** 교시 삭제 (ADMIN) */
    @OwnerOnly
    @DeleteMapping("/periods/{periodId}")
    public ResponseEntity<Void> deletePeriod(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long periodId) {
        settingService.deletePeriod(userId, periodId);
        return ResponseEntity.noContent().build();
    }

    // DTO

    @Getter @Setter
    public static class SchoolSettingRequest {
        private int periodDuration;
        private int breakDuration;
        private LocalTime lunchStartTime;
        private LocalTime lunchEndTime;
    }

    @Getter
    public static class SchoolSettingResponse {
        private final Long id;
        private final int periodDuration;
        private final int breakDuration;
        private final LocalTime lunchStartTime;
        private final LocalTime lunchEndTime;
        private final List<PeriodSettingResponse> periods;

        public SchoolSettingResponse(SchoolSetting s) {
            this.id = s.getId();
            this.periodDuration = s.getPeriodDuration();
            this.breakDuration = s.getBreakDuration();
            this.lunchStartTime = s.getLunchStartTime();
            this.lunchEndTime = s.getLunchEndTime();
            this.periods = s.getPeriods().stream()
                    .map(PeriodSettingResponse::new)
                    .collect(Collectors.toList());
        }
    }

    @Getter @Setter
    public static class PeriodSettingRequest {
        private int periodNumber;
        private LocalTime startTime;
        private LocalTime endTime;
    }

    @Getter
    public static class PeriodSettingResponse {
        private final Long id;
        private final int periodNumber;
        private final LocalTime startTime;
        private final LocalTime endTime;

        public PeriodSettingResponse(PeriodSetting p) {
            this.id = p.getId();
            this.periodNumber = p.getPeriodNumber();
            this.startTime = p.getStartTime();
            this.endTime = p.getEndTime();
        }
    }
}
