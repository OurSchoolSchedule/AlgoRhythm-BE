package com.rssolplan.edu.domain.school.setting;

import com.rssolplan.edu.domain.schedule.generation.TimetableRepository;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.global.exception.BadRequestException;
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
    private final TimetableRepository timetableRepository;
    private final AuthorizationService authService;

    /**
     * Returns the user's active-school settings.
     *
     * @throws NotFoundException if the user or settings do not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
    @Transactional(readOnly = true)
    public SchoolSetting getSetting(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        return settingRepo.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
    }

    /**
     * Creates or updates the active school's lesson, break, and lunch settings, returning
     * the saved settings. Existing period start and end times are not recalculated.
     *
     * @param periodDuration lesson duration in minutes
     * @param breakDuration break duration in minutes
     * @throws NotFoundException if the user or school does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
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

    /**
     * Returns the active school's configured periods in their stored collection order.
     *
     * @throws NotFoundException if the user or settings do not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
    @Transactional(readOnly = true)
    public List<PeriodSetting> getPeriods(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolSetting setting = settingRepo.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
        return setting.getPeriods();
    }

    /**
     * Adds a period to the active school's settings and returns it. No time-range or
     * duplicate-number validation is performed here; persistence constraint failures propagate.
     *
     * @throws NotFoundException if the user or settings do not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
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

    /**
     * Updates a period's start and end times in the active school and returns the period.
     * Its number is unchanged; no time-range validation is performed here.
     *
     * @throws NotFoundException if the user, settings, or period in those settings does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
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

    /**
     * Removes a period from the active school's settings only when no timetable references it.
     *
     * @throws NotFoundException if the user, settings, or period in those settings does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     * @throws BadRequestException if any timetable references the period
     */
    @Transactional
    public void deletePeriod(Long userId, Long periodId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolSetting setting = settingRepo.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
        PeriodSetting target = setting.getPeriods().stream()
                .filter(p -> p.getId().equals(periodId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("교시 설정을 찾을 수 없습니다."));

        if (timetableRepository.existsByPeriodSetting_Id(target.getId())) {
            throw new BadRequestException("해당 교시를 참조하는 시간표가 존재하여 삭제할 수 없습니다.");
        }

        setting.getPeriods().remove(target);
        settingRepo.save(setting);
    }
}
