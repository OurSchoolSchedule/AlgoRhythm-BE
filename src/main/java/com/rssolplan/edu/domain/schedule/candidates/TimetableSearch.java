package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import java.util.*;
import java.util.stream.IntStream;

/** Iterative CSP over each row's combination of distinct weekly slots; no recursive call stack. */
final class TimetableSearch {
    private final TimetableProblem input;
    private final SearchBudget budget;
    private final CandidateRanking ranking;
    private final BitSet[] teacherBusy, classBusy, layout;
    private final int[] rowAt, unitsLeftInRow, chosen, remainingHours;
    private final int[][] slotOrder;
    private final BitSet[] choices;

    TimetableSearch(TimetableProblem input, SearchBudget budget, CandidateRanking ranking) {
        this.input = input;
        this.budget = budget;
        this.ranking = ranking;
        teacherBusy = CandidateRanking.emptySets(input.teacherHours.length);
        classBusy = CandidateRanking.emptySets(input.classHours.length);
        layout = CandidateRanking.emptySets(input.rows.size());
        rowAt = new int[input.lessons];
        unitsLeftInRow = new int[input.lessons];
        choices = new BitSet[input.lessons];
        chosen = new int[input.lessons];
        remainingHours = input.teacherHours.clone();
        slotOrder = preferenceOrder();
        var ordered =
                IntStream.range(0, input.rows.size())
                        .boxed()
                        .sorted(
                                Comparator.comparingInt(this::slack)
                                        .thenComparing(
                                                i -> -input.rows.get(i).demand().weeklyPeriods()))
                        .toList();
        int depth = 0;
        for (int i : ordered) {
            for (int left = input.rows.get(i).demand().weeklyPeriods(); left > 0; left--) {
                rowAt[depth] = i;
                unitsLeftInRow[depth++] = left;
            }
        }
    }

    Stop run() {
        Random random = new Random(29);
        for (int seed = 0; seed < 8; seed++) {
            budget.check();
            var initial = new EdgeColorSeed(input, budget, random).construct();
            if (initial != null) ranking.offer(input, initial, budget);
            if (ranking.fourAtBound(input.objective)) return Stop.TOP_FOUR_AT_BOUND;
        }
        int depth = 0;
        while (depth >= 0) {
            budget.check();
            if (depth == rowAt.length) {
                ranking.offer(input, layout, budget);
                if (ranking.fourAtBound(input.objective)) return Stop.TOP_FOUR_AT_BOUND;
            } else {
                int i = rowAt[depth];
                if (choices[depth] == null) choices[depth] = available(depth, i);
                int next = select(choices[depth], input.rows.get(i).teacher());
                if (next >= 0) {
                    choices[depth].clear(next);
                    chosen[depth] = next;
                    occupy(i, next, true);
                    depth++;
                    continue;
                }
                choices[depth] = null;
            }
            if (--depth >= 0) occupy(rowAt[depth], chosen[depth], false);
        }
        return Stop.SEARCH_EXHAUSTED;
    }

    private int slack(int i) {
        var r = input.rows.get(i);
        return input.slots.size()
                - Math.max(
                        input.classHours[r.group()] + input.classClosed[r.group()].cardinality(),
                        input.teacherHours[r.teacher()] + input.blocked[r.teacher()].cardinality());
    }

    private BitSet available(int depth, int i) {
        var r = input.rows.get(i);
        BitSet available = new BitSet(input.slots.size());
        boolean newRow = depth == 0 || rowAt[depth - 1] != i;
        if (newRow && cannotBeatFourth()) return available;
        // Identical hours of a single subject have no identity: enumerate combinations once.
        available.set(newRow ? 0 : chosen[depth - 1] + 1, input.slots.size());
        available.andNot(input.classClosed[r.group()]);
        available.andNot(input.blocked[r.teacher()]);
        available.andNot(teacherBusy[r.teacher()]);
        available.andNot(classBusy[r.group()]);
        if (available.cardinality() < unitsLeftInRow[depth]) available.clear();
        return available;
    }

    // Optimistic bound: ignore class occupancy and competing wishes when estimating future WANTs.
    private boolean cannotBeatFourth() {
        if (ranking.best.size() != 4 || input.wishes.isEmpty()) return false;
        int possibleWants = 0, possibleAvoids = 0;
        BitSet submitters = new BitSet(), possiblySatisfied = new BitSet();
        for (var w : input.wishes) {
            budget.check();
            int t = w.teacher();
            if (w.kind() == Kind.WANT) {
                submitters.set(t);
                if (w.anyOf().stream()
                        .anyMatch(
                                s ->
                                        teacherBusy[t].get(s)
                                                || (remainingHours[t] > 0
                                                        && !input.blocked[t].get(s)))) {
                    possibleWants++;
                    possiblySatisfied.set(t);
                }
            } else if (w.anyOf().stream().anyMatch(s -> !teacherBusy[t].get(s))) possibleAvoids++;
        }
        var upper =
                new Score(
                        possibleWants,
                        0,
                        submitters.cardinality() - possiblySatisfied.cardinality(),
                        possibleAvoids,
                        0,
                        0);
        return CandidateRanking.quality(input.objective).compare(upper, ranking.best.get(3).score())
                > 0;
    }

    private int select(BitSet available, int teacher) {
        if (available.isEmpty()) return -1;
        if (slotOrder[teacher] == null) return available.nextSetBit(0);
        for (int s : slotOrder[teacher]) if (available.get(s)) return s;
        return -1;
    }

    private int[][] preferenceOrder() {
        int[][] order = new int[input.teacherHours.length][];
        input.slotPriorities.forEach(
                (t, values) -> {
                    budget.check();
                    order[t] =
                            IntStream.range(0, values.length)
                                    .boxed()
                                    .sorted(Comparator.comparingInt(s -> -values[s]))
                                    .mapToInt(Integer::intValue)
                                    .toArray();
                });
        return order;
    }

    private void occupy(int i, int slot, boolean value) {
        var r = input.rows.get(i);
        teacherBusy[r.teacher()].set(slot, value);
        classBusy[r.group()].set(slot, value);
        layout[i].set(slot, value);
        remainingHours[r.teacher()] += value ? -1 : 1;
    }
}
