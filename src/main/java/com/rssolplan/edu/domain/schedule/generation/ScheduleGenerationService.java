package com.rssolplan.edu.domain.schedule.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rssolplan.edu.domain.notification.NotificationService;
import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.generation.dto.TimetableGenerationRequestDto;
import com.rssolplan.edu.domain.schedule.generation.dto.TimetableSlotRequirementDto;
import com.rssolplan.edu.domain.schedule.generation.dto.candidate.ConfirmTimetableRequestDto;
import com.rssolplan.edu.domain.schedule.generation.dto.candidate.CandidateSchedule;
import com.rssolplan.edu.domain.schedule.generation.dto.candidate.GenerationOptionsDto;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableRequest;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableSet;
import com.rssolplan.edu.domain.schedule.generation.strategy.*;
import com.rssolplan.edu.domain.schedule.workavailability.TeacherAvailability;
import com.rssolplan.edu.domain.schedule.workavailability.TeacherAvailabilityRepository;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolClass;
import com.rssolplan.edu.domain.school.SchoolClassRepository;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.school.Subject;
import com.rssolplan.edu.domain.school.SubjectRepository;
import com.rssolplan.edu.domain.school.setting.PeriodSetting;
import com.rssolplan.edu.domain.school.setting.SchoolSettingRepository;
import com.rssolplan.edu.global.exception.BadRequestException;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.Getter;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleGenerationService {

    private final SchoolRepository schoolRepository;
    private final SchoolUserRepository schoolUserRepository;
    private final SchoolClassRepository schoolClassRepository;
    private final SubjectRepository subjectRepository;
    private final SchoolSettingRepository schoolSettingRepository;
    private final TeacherAvailabilityRepository teacherAvailabilityRepository;
    private final TimetableRepository timetableRepository;
    private final TimetableRequestRepository timetableRequestRepository;
    private final TimetableSetRepository timetableSetRepository;
    private final AuthorizationService authService;
    private final NotificationService notificationService;
    private final RedisTemplate<String, Object> redisTemplate;

    private final BalancedStrategy balancedStrategy;
    private final CoverageFirstStrategy coverageFirstStrategy;
    private final SeniorPriorityStrategy seniorPriorityStrategy;
    private final FairDistributionStrategy fairDistributionStrategy;

    private ObjectMapper objectMapper;

    @jakarta.annotation.PostConstruct
    public void init() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // =========================================================
    // 1. 시간표 생성 요청 (교사들에게 불가 교시 제출 요청)
    // =========================================================
    /**
     * Creates a REQUESTED timetable request in the user's active school and persists
     * notifications asking its teachers to submit unavailable periods.
     *
     * @throws NotFoundException if the user or school does not exist
     * @throws ForbiddenException if no school is active or the user lacks an ADMIN membership
     * @throws IllegalArgumentException if the notification service cannot find the school or requester
     */
    @Transactional
    public TimetableRequest requestTimetable(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolUser admin = authService.getSchoolUserOrThrow(userId, schoolId);

        if (admin.getPosition() != SchoolUser.Position.ADMIN) {
            throw new ForbiddenException("시간표 생성 요청 권한이 없습니다.");
        }

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));

        TimetableRequest request = TimetableRequest.builder()
                .school(school)
                .status(TimetableRequest.TimetableRequestStatus.REQUESTED)
                .build();

        timetableRequestRepository.save(request);

        notificationService.sendTimetableInputRequest(userId, schoolId);

        return request;
    }

    // =========================================================
    // 2. 후보 시간표 생성
    // =========================================================
    /**
     * Generates candidate timetables for a REQUESTED request in the active school,
     * caches them for one day, and marks the request GENERATED. Returns request and school
     * IDs, academic year, semester, cache key, and generated count; candidates are retrieved separately.
     * Strategy selection follows {@link #getStrategiesToUse(GenerationOptionsDto)}.
     * Redis failures propagate.
     *
     * @throws NotFoundException if the user or timetable request does not exist
     * @throws ForbiddenException if no school is active or the request belongs to another school
     * @throws IllegalStateException if the request is not REQUESTED or there are no teachers
     * @throws RuntimeException if candidate serialization fails
     */
    @Transactional
    public Map<String, Object> generateTimetable(Long userId, Long timetableRequestId,
                                                 TimetableGenerationRequestDto request) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);

        TimetableRequest timetableRequest = timetableRequestRepository.findById(timetableRequestId)
                .orElseThrow(() -> new NotFoundException("시간표 요청을 찾을 수 없습니다."));

        if (!timetableRequest.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 시간표 요청이 아닙니다.");
        }

        if (timetableRequest.getStatus() != TimetableRequest.TimetableRequestStatus.REQUESTED) {
            throw new IllegalStateException("아직 요청 상태가 아닙니다.");
        }

        // 교사 목록 로드
        List<SchoolUser> teachers = schoolUserRepository.findBySchool_IdAndPosition(
                schoolId, SchoolUser.Position.TEACHER);

        // 교사 불가 교시 로드
        List<Long> teacherIds = teachers.stream().map(SchoolUser::getId).collect(Collectors.toList());
        List<TeacherAvailability> unavailabilities = teacherIds.stream()
                .flatMap(id -> teacherAvailabilityRepository.findBySchoolUser_Id(id).stream())
                .collect(Collectors.toList());

        // 교시 설정 스냅샷
        TimetableSettingSnapshot settings = TimetableSettingSnapshot.builder()
                .schoolId(schoolId)
                .academicYear(request.getAcademicYear())
                .semester(request.getSemester())
                .slotRequirements(request.getSlotRequirements())
                .build();

        // 교사 메타데이터 매핑
        Map<Long, String> usernameMap = schoolUserRepository
                .findSchoolUserIdAndUsernameBySchoolId(schoolId)
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (String) row[1]));

        Map<Long, LocalDate> hireDateMap = teachers.stream()
                .collect(Collectors.toMap(
                        SchoolUser::getId,
                        t -> t.getHireDate() != null ? t.getHireDate() : LocalDate.now()
                ));

        // 전략 기반 후보 생성
        List<CandidateSchedule> candidates = generateCandidatesWithStrategies(
                schoolId, settings, unavailabilities, teachers, usernameMap, hireDateMap,
                request.getGenerationOptions());

        // Redis에 후보 저장
        String redisKey = saveCandidatesToRedis(schoolId, candidates);

        // 상태 업데이트
        timetableRequest.setStatus(TimetableRequest.TimetableRequestStatus.GENERATED);
        timetableRequest.setCandidateTimetableKey(redisKey);
        timetableRequestRepository.save(timetableRequest);

        return Map.of(
                "timetableRequestId", timetableRequest.getId(),
                "schoolId", schoolId,
                "academicYear", request.getAcademicYear(),
                "semester", request.getSemester(),
                "candidateTimetableKey", redisKey,
                "generatedCount", candidates.size()
        );
    }

    // =========================================================
    // 3. 후보 시간표 조회
    // =========================================================
    /**
     * Returns candidates stored under the supplied Redis key. Redis access failures propagate.
     *
     * @throws NotFoundException if the key is missing or expired
     * @throws RuntimeException if the cached JSON cannot be decoded
     */
    public List<CandidateSchedule> getCandidates(String redisKey) {
        String json = (String) redisTemplate.opsForValue().get(redisKey);
        if (json == null) throw new NotFoundException("생성된 시간표가 없습니다.");
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Redis 캐시 읽기 실패", e);
        }
    }

    // =========================================================
    // 4. 시간표 확정
    // =========================================================
    /**
     * Replaces the active school's timetable for the supplied year and semester with a cached
     * candidate, marks the request CONFIRMED, and deletes its candidate cache. Skips shifts
     * without a teacher or a configured period. Returns the existing or newly created timetable
     * set; the supplied dates apply only when creating a set. Redis failures propagate.
     *
     * @param dto selection with a zero-based candidate index and the target term and dates
     * @throws NotFoundException if the user, request, school, settings, candidates, or referenced entities are missing
     * @throws ForbiddenException if no school is active or the request belongs to another school
     * @throws IllegalStateException if the request is not GENERATED, candidates are empty, or the index is too large
     * @throws IndexOutOfBoundsException if the candidate index is negative
     * @throws BadRequestException if an assigned shift with a configured period has no subject ID
     * @throws RuntimeException if cached candidate JSON cannot be decoded
     */
    @Transactional
    public TimetableSet confirmTimetable(Long userId, Long timetableRequestId,
                                        ConfirmTimetableRequestDto dto) {
        int candidateIndex = dto.getCandidateIndex();
        int academicYear = dto.getAcademicYear();
        int semester = dto.getSemester();
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);

        TimetableRequest timetableRequest = timetableRequestRepository.findById(timetableRequestId)
                .orElseThrow(() -> new NotFoundException("시간표 요청을 찾을 수 없습니다."));

        if (!timetableRequest.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 시간표 요청이 아닙니다.");
        }

        if (timetableRequest.getStatus() != TimetableRequest.TimetableRequestStatus.GENERATED) {
            throw new IllegalStateException("아직 후보 시간표가 생성되지 않았습니다.");
        }

        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));

        // TimetableSet 생성 또는 조회
        TimetableSet timetableSet = timetableSetRepository
                .findBySchool_IdAndAcademicYearAndSemester(schoolId, academicYear, semester)
                .orElseGet(() -> timetableSetRepository.save(
                        TimetableSet.builder()
                                .school(school)
                                .academicYear(academicYear)
                                .semester(semester)
                                .startDate(dto.getStartDate())
                                .endDate(dto.getEndDate())
                                .build()));

        // 후보 조회
        List<CandidateSchedule> candidates = getCandidates(timetableRequest.getCandidateTimetableKey());
        if (candidates.isEmpty() || candidateIndex >= candidates.size()) {
            throw new IllegalStateException("유효하지 않은 후보 인덱스입니다.");
        }

        CandidateSchedule selected = candidates.get(candidateIndex);

        // 교시 ID → PeriodSetting 매핑
        var schoolSetting = schoolSettingRepository.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));
        Map<Integer, PeriodSetting> periodMap = schoolSetting.getPeriods().stream()
                .collect(Collectors.toMap(PeriodSetting::getPeriodNumber, p -> p));

        // 삭제 전에 배정된 모든 shift가 유효한지 검증
        for (var shift : selected.getShifts()) {
            if (shift.getSchoolUserId() == null) continue;
            if (periodMap.get(shift.getPeriodNumber()) == null) continue;
            if (shift.getSubjectId() == null) {
                throw new BadRequestException("후보 시간표에 과목이 없는 교시가 포함되어 있습니다. (dayOfWeek=" +
                        shift.getDayOfWeek() + ", period=" + shift.getPeriodNumber() + ")");
            }
        }

        // 기존 해당 연도/학기 시간표 삭제
        List<Timetable> existing = timetableRepository
                .findBySchool_IdAndAcademicYearAndSemester(schoolId, academicYear, semester);
        timetableRepository.deleteAll(existing);

        // CandidateShift → Timetable 변환
        for (var shift : selected.getShifts()) {
            if (shift.getSchoolUserId() == null) continue; // UNASSIGNED 건너뜀

            PeriodSetting periodSetting = periodMap.get(shift.getPeriodNumber());
            if (periodSetting == null) continue;

            SchoolUser teacher = schoolUserRepository.findById(shift.getSchoolUserId())
                    .orElseThrow(() -> new NotFoundException("교사를 찾을 수 없습니다."));
            SchoolClass schoolClass = schoolClassRepository.findById(shift.getSchoolClassId())
                    .orElseThrow(() -> new NotFoundException("학급을 찾을 수 없습니다."));
            Subject subject = subjectRepository.findById(shift.getSubjectId())
                    .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다."));

            Timetable timetable = Timetable.builder()
                    .school(school)
                    .academicYear(academicYear)
                    .semester(semester)
                    .schoolClass(schoolClass)
                    .periodSetting(periodSetting)
                    .dayOfWeek(shift.getDayOfWeek())
                    .subject(subject)
                    .teacher(teacher)
                    .build();

            timetableRepository.save(timetable);
        }

        // TimetableRequest 상태 업데이트
        timetableRequest.setStatus(TimetableRequest.TimetableRequestStatus.CONFIRMED);
        timetableRequest.setTimetableSet(timetableSet);
        timetableRequestRepository.save(timetableRequest);

        // Redis 정리
        redisTemplate.delete(timetableRequest.getCandidateTimetableKey());

        return timetableSet;
    }

    // =========================================================
    // 미제출 교사 목록 조회 (불가 교시를 하나도 제출하지 않은 교사)
    // =========================================================
    /**
     * Returns user IDs, rather than membership IDs, for teachers in the active school
     * with no unavailable periods recorded.
     *
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the user does not exist
     * @throws com.rssolplan.edu.global.exception.ForbiddenException if no school is active
     */
    @Transactional(readOnly = true)
    public List<Long> getTeachersWithoutAvailability(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        List<SchoolUser> teachers = schoolUserRepository.findBySchool_IdAndPosition(
                schoolId, SchoolUser.Position.TEACHER);
        return teachers.stream()
                .filter(t -> teacherAvailabilityRepository.findBySchoolUser_Id(t.getId()).isEmpty())
                .map(t -> t.getUser().getId())
                .collect(Collectors.toList());
    }

    // =========================================================
    // 내부 헬퍼
    // =========================================================

    /**
     * Generates one candidate per selected strategy and calculates counts and coverage
     * percentage for each result. Metadata maps are keyed by SchoolUser ID; candidates
     * may contain unassigned slots. Selection follows {@link #getStrategiesToUse(GenerationOptionsDto)}.
     *
     * @throws IllegalStateException if the teacher list is empty
     */
    public List<CandidateSchedule> generateCandidatesWithStrategies(
            Long schoolId,
            TimetableSettingSnapshot settings,
            List<TeacherAvailability> unavailabilities,
            List<SchoolUser> teachers,
            Map<Long, String> usernameMap,
            Map<Long, LocalDate> hireDateMap,
            GenerationOptionsDto options) {

        if (teachers.isEmpty()) {
            throw new IllegalStateException("배정 가능한 교사가 없습니다.");
        }

        List<ScheduleGenerationStrategy> strategiesToUse = getStrategiesToUse(options);
        List<CandidateSchedule> result = new ArrayList<>();

        for (ScheduleGenerationStrategy strategy : strategiesToUse) {
            log.info("전략 '{}' 으로 후보 시간표 생성 중...", strategy.getStrategyName());

            CandidateSchedule candidate = strategy.generate(
                    schoolId, settings, unavailabilities, teachers, usernameMap, hireDateMap);

            candidate.setStrategyName(strategy.getStrategyName());
            candidate.setStrategyDescription(strategy.getDescription());
            candidate.calculateMetadata();

            log.info("전략 '{}' 완료 - 배정률: {}%, 빈자리: {}개",
                    strategy.getStrategyName(), candidate.getCoverageRate(), candidate.getUnassignedCount());

            result.add(candidate);
        }

        return result;
    }

    /**
     * Returns requested strategies in order, retaining duplicates, then appends unrequested
     * strategies in BALANCED, COVERAGE_FIRST, SENIOR_PRIORITY, FAIR_DISTRIBUTION order
     * until there are four entries. Null options or a null strategy list select all four.
     * The candidate-count option is unused; requested lists longer than four are retained.
     *
     * @throws NullPointerException if the strategy list contains null
     */
    private List<ScheduleGenerationStrategy> getStrategiesToUse(GenerationOptionsDto options) {
        Map<GenerationOptionsDto.GenerationStrategy, ScheduleGenerationStrategy> strategyMap = Map.of(
                GenerationOptionsDto.GenerationStrategy.BALANCED, balancedStrategy,
                GenerationOptionsDto.GenerationStrategy.COVERAGE_FIRST, coverageFirstStrategy,
                GenerationOptionsDto.GenerationStrategy.SENIOR_PRIORITY, seniorPriorityStrategy,
                GenerationOptionsDto.GenerationStrategy.FAIR_DISTRIBUTION, fairDistributionStrategy
        );

        List<GenerationOptionsDto.GenerationStrategy> allOrder = List.of(
                GenerationOptionsDto.GenerationStrategy.BALANCED,
                GenerationOptionsDto.GenerationStrategy.COVERAGE_FIRST,
                GenerationOptionsDto.GenerationStrategy.SENIOR_PRIORITY,
                GenerationOptionsDto.GenerationStrategy.FAIR_DISTRIBUTION
        );

        List<ScheduleGenerationStrategy> result = new ArrayList<>();
        Set<GenerationOptionsDto.GenerationStrategy> requested = new LinkedHashSet<>();

        if (options != null && options.getStrategies() != null) {
            for (var s : options.getStrategies()) {
                if (strategyMap.containsKey(s)) {
                    result.add(strategyMap.get(s));
                    requested.add(s);
                }
            }
        }

        for (var s : allOrder) {
            if (!requested.contains(s) && result.size() < 4) {
                result.add(strategyMap.get(s));
            }
        }

        return result;
    }

    /**
     * Stores candidates as JSON under a new school-specific key for one day and returns
     * that key. Redis access failures propagate.
     *
     * @throws RuntimeException if JSON serialization fails
     */
    private String saveCandidatesToRedis(Long schoolId, List<CandidateSchedule> candidates) {
        String key = "school:candidate:" + schoolId + ":" + UUID.randomUUID();
        try {
            String json = objectMapper.writeValueAsString(candidates);
            redisTemplate.opsForValue().set(key, json, Duration.ofDays(1));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Redis 캐시 저장 실패", e);
        }
        return key;
    }

    // =========================================================
    // TimetableSettingSnapshot (교시 기반)
    // =========================================================
    @Getter
    @Builder
    public static class TimetableSettingSnapshot {
        private Long schoolId;
        private int academicYear;
        private int semester;
        /** 생성 요청에 포함된 슬롯 목록 */
        private List<TimetableSlotRequirementDto> slotRequirements;
    }
}
