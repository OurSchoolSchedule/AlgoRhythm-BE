package com.rssolplan.edu.domain.schedule.workavailability;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.workavailability.dto.TeacherAvailabilityRequestDto;
import com.rssolplan.edu.domain.schedule.workavailability.dto.TeacherAvailabilityResponseDto;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TeacherAvailabilityService {

    private final TeacherAvailabilityRepository availabilityRepository;
    private final SchoolUserRepository schoolUserRepository;
    private final AuthorizationService authService;

    /** 내 불가 교시 목록 조회 */
    @Transactional(readOnly = true)
    public List<TeacherAvailabilityResponseDto> getMyUnavailabilities(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolUser me = authService.getSchoolUserOrThrow(userId, schoolId);
        return availabilityRepository.findBySchoolUser_Id(me.getId())
                .stream().map(TeacherAvailabilityResponseDto::new).collect(Collectors.toList());
    }

    /** 특정 교사의 불가 교시 목록 조회 (ADMIN용) */
    @Transactional(readOnly = true)
    public List<TeacherAvailabilityResponseDto> getTeacherUnavailabilities(Long schoolUserId) {
        return availabilityRepository.findBySchoolUser_Id(schoolUserId)
                .stream().map(TeacherAvailabilityResponseDto::new).collect(Collectors.toList());
    }

    /** 학교 전체 교사 불가 교시 조회 */
    @Transactional(readOnly = true)
    public List<TeacherAvailabilityResponseDto> getAllUnavailabilities(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        List<SchoolUser> teachers = schoolUserRepository.findBySchool_IdAndPosition(
                schoolId, SchoolUser.Position.TEACHER);
        List<Long> teacherIds = teachers.stream().map(SchoolUser::getId).collect(Collectors.toList());
        return teacherIds.stream()
                .flatMap(id -> availabilityRepository.findBySchoolUser_Id(id).stream())
                .map(TeacherAvailabilityResponseDto::new)
                .collect(Collectors.toList());
    }

    /** 불가 교시 등록 (기존 것은 유지, 중복은 건너뜀) */
    @Transactional
    public List<TeacherAvailabilityResponseDto> addUnavailabilities(Long userId,
                                                                    TeacherAvailabilityRequestDto request) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolUser me = authService.getSchoolUserOrThrow(userId, schoolId);

        return request.getUnavailabilities().stream()
                .filter(item -> !availabilityRepository.existsBySchoolUser_IdAndDayOfWeekAndPeriodNumber(
                        me.getId(), item.getDayOfWeek(), item.getPeriodNumber()))
                .map(item -> {
                    TeacherAvailability saved = availabilityRepository.save(
                            TeacherAvailability.builder()
                                    .schoolUser(me)
                                    .dayOfWeek(item.getDayOfWeek())
                                    .periodNumber(item.getPeriodNumber())
                                    .reason(item.getReason())
                                    .build());
                    return new TeacherAvailabilityResponseDto(saved);
                })
                .collect(Collectors.toList());
    }

    /** 불가 교시 전체 교체 (기존 삭제 후 재등록) */
    @Transactional
    public List<TeacherAvailabilityResponseDto> replaceUnavailabilities(Long userId,
                                                                        TeacherAvailabilityRequestDto request) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolUser me = authService.getSchoolUserOrThrow(userId, schoolId);

        availabilityRepository.deleteBySchoolUser_Id(me.getId());

        return request.getUnavailabilities().stream()
                .map(item -> {
                    TeacherAvailability saved = availabilityRepository.save(
                            TeacherAvailability.builder()
                                    .schoolUser(me)
                                    .dayOfWeek(item.getDayOfWeek())
                                    .periodNumber(item.getPeriodNumber())
                                    .reason(item.getReason())
                                    .build());
                    return new TeacherAvailabilityResponseDto(saved);
                })
                .collect(Collectors.toList());
    }

    /** 특정 불가 교시 삭제 */
    @Transactional
    public void deleteUnavailability(Long userId, Long availabilityId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolUser me = authService.getSchoolUserOrThrow(userId, schoolId);

        TeacherAvailability avail = availabilityRepository.findById(availabilityId)
                .orElseThrow(() -> new NotFoundException("불가 교시 정보를 찾을 수 없습니다."));

        if (!avail.getSchoolUser().getId().equals(me.getId())) {
            throw new SecurityException("본인의 불가 교시만 삭제할 수 있습니다.");
        }

        availabilityRepository.delete(avail);
    }
}
