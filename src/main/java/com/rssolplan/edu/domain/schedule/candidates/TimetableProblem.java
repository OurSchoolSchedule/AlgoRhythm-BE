package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import com.rssolplan.edu.domain.schedule.loadimport.LoadWorkbookParser.Demand;
import com.rssolplan.edu.global.exception.BadRequestException;

import java.util.*;

/** Immutable input for one search. All indices refer to this snapshot only. */
final class TimetableProblem {
    static final int MAX_SLOTS = 128;

    record Row(Demand demand, int teacher, int group) {}

    record Wish(int teacher, Kind kind, Set<Integer> anyOf) {}

    final Objective objective;
    final List<Slot> slots;
    final List<Row> rows;
    final List<Wish> wishes;
    final Map<Integer, int[]> slotPriorities;
    final BitSet[] blocked, classClosed;
    final int[] teacherHours, classHours;
    final int lessons;
    final String capacityFailure;

    TimetableProblem(
            List<Demand> demands,
            List<Slot> slots,
            Map<String, Set<Slot>> forbidden,
            List<Preference> preferences) {
        this(demands, slots, forbidden, preferences, Objective.BALANCED);
    }

    TimetableProblem(
            List<Demand> demands,
            List<Slot> slots,
            Map<String, Set<Slot>> forbidden,
            List<Preference> preferences,
            Objective objective) {
        this(demands, slots, forbidden, preferences, objective, Map.of());
    }

    TimetableProblem(
            List<Demand> demands, List<Slot> slots, Map<String, Set<Slot>> forbidden,
            List<Preference> preferences, Objective objective, Map<String, BitSet> closedByClass) {
        this.objective = objective;
        if (demands.isEmpty() || demands.size() > 8192)
            throw new BadRequestException("편성은 시수 배당 1~8,192건을 지원합니다.");
        this.slots = List.copyOf(slots);
        Map<Slot, Integer> indices = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) indices.put(slots.get(i), i);
        Map<String, Integer> teachers = new HashMap<>(), classes = new HashMap<>();
        var ordered =
                demands.stream()
                        .sorted(
                                Comparator.comparing(Demand::teacherId)
                                        .thenComparing(Demand::classId)
                                        .thenComparing(Demand::subjectId))
                        .toList();
        List<Row> rows = new ArrayList<>();
        for (Demand d : ordered)
            rows.add(
                    new Row(
                            d,
                            teachers.computeIfAbsent(d.teacherId(), k -> teachers.size()),
                            classes.computeIfAbsent(d.classId(), k -> classes.size())));
        this.rows = List.copyOf(rows);
        teacherHours = new int[teachers.size()];
        classHours = new int[classes.size()];
        classClosed = new BitSet[classes.size()];
        classes.forEach((id, index) -> classClosed[index] = closedByClass.getOrDefault(id, new BitSet()));
        blocked = new BitSet[teachers.size()];
        teachers.forEach(
                (id, index) -> {
                    var bits = new BitSet(slots.size());
                    for (var slot : forbidden.getOrDefault(id, Set.of())) {
                        Integer s = indices.get(slot);
                        if (s != null) bits.set(s);
                    }
                    blocked[index] = bits;
                });
        int sum = 0;
        String failure = null;
        for (var row : rows) {
            var d = row.demand();
            sum += d.weeklyPeriods();
            teacherHours[row.teacher()] += d.weeklyPeriods();
            classHours[row.group()] += d.weeklyPeriods();
            if (failure != null) continue;
            if (teacherHours[row.teacher()] > slots.size() - blocked[row.teacher()].cardinality())
                failure =
                        d.sourceCell() + " · 교사 " + d.teacherId() + "의 주당 시수가 배정 가능한 교시 수를 초과합니다.";
            else if (classHours[row.group()] > slots.size() - classClosed[row.group()].cardinality())
                failure = d.sourceCell() + " · 학급 " + d.classId() + "의 주당 시수가 편성 교시 수를 초과합니다.";
        }
        if (failure == null && sum > 8192)
            throw new BadRequestException("1회 편성은 주간 8,192차시까지 지원합니다.");
        capacityFailure = failure;
        lessons = sum;
        if (preferences == null) preferences = List.of();
        if (preferences.size() > 2048) throw new BadRequestException("선호 묶음은 최대 2,048개입니다.");
        Set<Wish> distinct = new LinkedHashSet<>();
        for (var pref : preferences) {
            if (pref == null
                    || pref.kind() == null
                    || !teachers.containsKey(pref.teacherId())
                    || pref.anyOf() == null
                    || pref.anyOf().isEmpty()
                    || pref.anyOf().size() > MAX_SLOTS)
                throw new BadRequestException("배당이 있는 교사 ID, WANT/AVOID, 하나 이상의 선호 교시를 지정하세요.");
            Set<Integer> any = new HashSet<>();
            for (var slot : pref.anyOf()) {
                Integer index = indices.get(slot);
                if (index == null) throw new BadRequestException("선호 교시가 이번 편성 교시 범위 밖에 있습니다.");
                any.add(index);
            }
            distinct.add(new Wish(teachers.get(pref.teacherId()), pref.kind(), Set.copyOf(any)));
        }
        wishes = List.copyOf(distinct);
        slotPriorities = new HashMap<>();
        for (var wish : wishes) {
            int[] weights =
                    slotPriorities.computeIfAbsent(wish.teacher(), t -> new int[slots.size()]);
            for (int slot : wish.anyOf()) weights[slot] += wish.kind() == Kind.WANT ? 1 : -1;
        }
    }
}
