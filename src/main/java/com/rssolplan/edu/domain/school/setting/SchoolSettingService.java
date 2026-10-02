package com.rssolplan.edu.domain.school.setting;

import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SchoolSettingService {

    private final SchoolSettingRepository settingRepo;
    private final SchoolRepository schoolRepo;
    private final AuthorizationService authService;

    @Transactional(readOnly = true)
    public SchoolSetting getSetting(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        return settingRepo.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
    }

    @Transactional
    public SchoolSetting createOrUpdateSetting(Long userId, int periodDuration, int breakDuration,
                                               LocalTime lunchStartTime, LocalTime lunchEndTime) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        School school = schoolRepo.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));

        SchoolSetting setting = settingRepo.findBySchool_Id(schoolId).orElseGet(() ->
                SchoolSetting.builder().school(school).build()
        );
        setting.setPeriodDuration(periodDuration);
        setting.setBreakDuration(breakDuration);
        setting.setLunchStartTime(lunchStartTime);
        setting.setLunchEndTime(lunchEndTime);
        return settingRepo.save(setting);
    }

    @Transactional(readOnly = true)
    public List<PeriodSetting> getPeriods(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolSetting setting = settingRepo.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
        return setting.getPeriods();
    }

    @Transactional
    public PeriodSetting addPeriod(Long userId, int periodNumber, LocalTime startTime, LocalTime endTime) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolSetting setting = settingRepo.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
        PeriodSetting period = PeriodSetting.builder()
                .schoolSetting(setting)
                .periodNumber(periodNumber)
                .startTime(startTime)
                .endTime(endTime)
                .build();
        setting.addPeriod(period);
        settingRepo.save(setting);
        return period;
    }

    @Transactional
    public PeriodSetting updatePeriod(Long userId, Long periodId, LocalTime startTime, LocalTime endTime) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolSetting setting = settingRepo.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
        PeriodSetting period = setting.getPeriods().stream()
                .filter(p -> p.getId().equals(periodId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("교시 설정을 찾을 수 없습니다."));
        period.setStartTime(startTime);
        period.setEndTime(endTime);
        settingRepo.save(setting);
        return period;
    }

    @Transactional
    public void deletePeriod(Long userId, Long periodId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolSetting setting = settingRepo.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
        setting.getPeriods().removeIf(p -> p.getId().equals(periodId));
        settingRepo.save(setting);
    }
}
