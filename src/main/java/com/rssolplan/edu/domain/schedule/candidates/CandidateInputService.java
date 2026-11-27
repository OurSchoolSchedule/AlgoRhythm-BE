package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.loadimport.*;
import com.rssolplan.edu.domain.schedule.operations.ScheduleConflict;
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

    record Snapshot(
            long schoolId,
            int year,
            Long termId,
            LoadWorkbookParser.Result load,
            Request request,
            List<Slot> slots,
            Map<String, Set<Slot>> blocked,
            Map<Integer, Bell> bells,
            Map<String, Integer> classGrades) {
        TimetableProblem problem() {
            return new TimetableProblem(
                    load.demands(), slots, blocked, request.preferences(), request.objective(),
                    GradeCalendar.closedSlots(slots, classGrades, request.gradeLastPeriods()));
        }
    }

    record Bell(long id, java.time.LocalTime start, java.time.LocalTime end) {}

    @Transactional(readOnly = true)
    public Snapshot freeze(Long userId, Long termId, Integer year, Request request) {
        if (request == null || request.validatedLoadId() == null)
            throw new BadRequestException("validatedLoadId가 필요합니다.");
        if (termId == null && (year == null || year < 1900 || year > 9999))
            throw new BadRequestException("학년도 범위를 확인하세요.");
        var catalog = catalogs.catalog(userId, termId, year, Map.of());
        var preview = previews.read(catalog, termId, request.validatedLoadId());
        return current(catalog, termId, preview, request, classGrades(catalog), false);
    }

    public Snapshot refresh(Long userId, long termId, Snapshot original) {
        var catalog = catalogs.catalog(userId, termId, null, Map.of());
        if (catalog.academicYear() != original.year() || catalog.schoolId() != original.schoolId())
            throw new IllegalStateException("학기 범위가 변경되었습니다.");
        catalog.verify(original.load().demands());
        var grades = classGrades(catalog);
        if (original.classGrades() != null && !original.classGrades().equals(grades))
            throw new ScheduleConflict("STALE_INPUT", null, "학급 명부 또는 학년이 변경되었습니다. 다시 생성하세요.");
        return current(catalog, termId, original.load(), original.request(), grades, original.classGrades() == null);
    }

    private static Map<String, Integer> classGrades(LoadCatalog catalog) {
        Map<String, Integer> grades = new TreeMap<>();
        catalog.classes().forEach((room, id) -> grades.put(id.toString(), room.grade()));
        return grades;
    }

    private Snapshot current(
            LoadCatalog catalog, Long termId, LoadWorkbookParser.Result preview, Request request,
            Map<String, Integer> classGrades, boolean historical) {
        var setting =
                settings.findBySchool_Id(catalog.schoolId())
                        .orElseThrow(() -> new IllegalStateException("학교 교시 설정을 먼저 등록하세요."));
        var periods = setting.getPeriods();
        var requested = request.teachingSlots();
        if (request.gradeLastPeriods() != null) {
            var union = GradeCalendar.union(request.gradeLastPeriods(), classGrades, periods);
            if (requested != null && !teachingSlots(periods, requested).equals(union))
                throw new BadRequestException("teachingSlots가 학년별 운영 교시 합집합과 일치하지 않습니다.");
            requested = union;
        }
        var slots = teachingSlots(periods, requested, historical);
        var closed = GradeCalendar.closedSlots(slots, classGrades, request.gradeLastPeriods());
        Map<String, Integer> hours = new HashMap<>();
        preview.demands().forEach(d -> hours.merge(d.classId(), d.weeklyPeriods(), Integer::sum));
        for (var room : catalog.classes().entrySet()) {
            String classId = room.getValue().toString();
            int capacity = slots.size() - closed.getOrDefault(classId, new BitSet()).cardinality();
            int required = hours.getOrDefault(classId, 0);
            if (required > capacity)
                throw new BadRequestException("%d학년 %d반은 %d차시가 필요하지만 운영 교시는 %d칸으로 %d차시 초과합니다."
                        .formatted(room.getKey().grade(), room.getKey().number(), required, capacity, required - capacity));
        }
        Map<String, Set<Slot>> blocked = new HashMap<>();
        for (var row : unavailability.findBySchoolUser_School_Id(catalog.schoolId()))
            blocked.computeIfAbsent(Long.toString(catalog.teachingId(row.getSchoolUser().getId())), k -> new HashSet<>())
                    .add(new Slot(row.getDayOfWeek(), row.getPeriodNumber()));
        Map<Integer, Bell> bells = new TreeMap<>();
        for (var p : setting.getPeriods())
            bells.put(p.getPeriodNumber(), new Bell(p.getId(), p.getStartTime(), p.getEndTime()));
        return new Snapshot(
                catalog.schoolId(),
                catalog.academicYear(),
                termId,
                preview,
                request,
                slots,
                blocked,
                bells,
                classGrades);
    }

    static List<Slot> teachingSlots(List<PeriodSetting> periods, List<Slot> requested) {
        return teachingSlots(periods, requested, false);
    }

    private static List<Slot> teachingSlots(List<PeriodSetting> periods, List<Slot> requested, boolean historical) {
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
            if (!historical && s != null && s.day() != null && s.day().getValue() > 5)
                throw new BadRequestException("새 시간표의 운영 요일은 월요일부터 금요일까지입니다.");
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
