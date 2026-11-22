package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.loadimport.*;
import com.rssolplan.edu.domain.schedule.workavailability.TeacherAvailabilityRepository;
import com.rssolplan.edu.domain.school.setting.*;
import com.rssolplan.edu.global.exception.BadRequestException;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
class CandidateInputService {
    private final LoadValidationService catalogs;
    private final LoadPreviewStore previews;
    private final SchoolSettingRepository settings;
    private final TeacherAvailabilityRepository unavailability;

    @Transactional(readOnly = true)
    public TimetableProblem load(Long userId, Long termId, Integer year, Request request) {
        var catalog = catalogs.catalog(userId, termId, year, Map.of());
        var preview = previews.read(catalog, termId, request.validatedLoadId());
        var setting =
                settings.findBySchool_Id(catalog.schoolId())
                        .orElseThrow(() -> new IllegalStateException("학교 교시 설정을 먼저 등록하세요."));
        var slots = teachingSlots(setting.getPeriods(), request.teachingSlots());
        Map<String, Set<Slot>> blocked = new HashMap<>();
        for (var row : unavailability.findBySchoolUser_School_Id(catalog.schoolId()))
            blocked.computeIfAbsent(row.getSchoolUser().getId().toString(), k -> new HashSet<>())
                    .add(new Slot(row.getDayOfWeek(), row.getPeriodNumber()));
        return new TimetableProblem(preview.demands(), slots, blocked, request.preferences());
    }

    static List<Slot> teachingSlots(List<PeriodSetting> periods, List<Slot> requested) {
        Map<Integer, PeriodSetting> byNumber = new HashMap<>();
        for (var p : periods) {
            if (p.getPeriodNumber() < 1
                    || p.getStartTime() == null
                    || p.getEndTime() == null
                    || !p.getEndTime().isAfter(p.getStartTime())
                    || byNumber.put(p.getPeriodNumber(), p) != null)
                throw new IllegalStateException("학교 교시 번호와 시작·종료 시각을 확인하세요.");
        }
        if (byNumber.isEmpty()) throw new IllegalStateException("학교 교시 설정이 비어 있습니다.");
        if (requested == null)
            requested =
                    List.of(
                                    DayOfWeek.MON,
                                    DayOfWeek.TUE,
                                    DayOfWeek.WED,
                                    DayOfWeek.THU,
                                    DayOfWeek.FRI)
                            .stream()
                            .flatMap(day -> byNumber.keySet().stream().map(n -> new Slot(day, n)))
                            .toList();
        if (requested.isEmpty() || requested.size() > TimetableProblem.MAX_SLOTS)
            throw new BadRequestException(
                    "편성 교시는 주간 1~" + TimetableProblem.MAX_SLOTS + "개를 지정하세요.");
        Set<Slot> distinct = new TreeSet<>();
        for (var s : requested) {
            if (s == null
                    || s.day() == null
                    || !byNumber.containsKey(s.period())
                    || !distinct.add(s))
                throw new BadRequestException("편성 교시의 요일·학교 교시 번호·중복을 확인하세요.");
        }
        for (var day : DayOfWeek.values()) {
            var ordered =
                    distinct.stream()
                            .filter(s -> s.day() == day)
                            .map(s -> byNumber.get(s.period()))
                            .sorted(Comparator.comparing(PeriodSetting::getStartTime))
                            .toList();
            for (int i = 1; i < ordered.size(); i++)
                if (ordered.get(i - 1).getEndTime().isAfter(ordered.get(i).getStartTime()))
                    throw new IllegalStateException(day + "에 실제 시간이 겹치는 학교 교시가 있습니다.");
        }
        return List.copyOf(distinct);
    }
}
