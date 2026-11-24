package com.rssolplan.edu.domain.schedule.candidates;

import java.util.*;

/**
 * Insert one teacher-class edge per lesson. Two-colour swaps free a slot when simple insertion
 * fails.
 */
final class EdgeColorSeed {
    private final TimetableProblem input;
    private final int[][] teacherAt, classAt;
    private final SearchBudget budget;
    private final int[] colours;
    private final Random random;

    private record Move(int row, int from, int to) {}

    EdgeColorSeed(TimetableProblem input, SearchBudget budget, Random random) {
        this.input = input;
        this.budget = budget;
        this.random = random;
        colours = sequence(input.slots.size());
        teacherAt = empty(input.teacherHours.length, colours.length);
        classAt = empty(input.classHours.length, colours.length);
    }

    BitSet[] construct() {
        int[] order = sequence(input.rows.size());
        shuffle(order);
        for (int row : order) {
            budget.check();
            shuffle(colours);
            for (int n = 0; n < input.rows.get(row).demand().weeklyPeriods(); n++)
                if (!insert(row)) return null;
        }
        BitSet[] result = CandidateRanking.emptySets(order.length);
        for (int[] slots : teacherAt) {
            budget.check();
            for (int s = 0; s < slots.length; s++) if (slots[s] >= 0) result[slots[s]].set(s);
        }
        return result;
    }

    private boolean insert(int id) {
        var row = input.rows.get(id);
        int t = row.teacher(), group = row.group();
        budget.check();
        int best = -1;
        int[] priorities = input.slotPriorities.get(t);
        for (int s : colours) {
            if (teacherAt[t][s] >= 0 || classAt[group][s] >= 0 || input.blocked[t].get(s)) continue;
            if (priorities == null) {
                best = s;
                break;
            }
            if (best < 0 || priorities[s] > priorities[best]) best = s;
        }
        if (best >= 0) {
            assign(id, best, id);
            return true;
        }
        // a is free on the teacher side, b on the class side. The alternating path
        // from t cannot reach group: it enters classes on b, which is free at group.
        for (int a : colours) {
            if (teacherAt[t][a] >= 0 || input.blocked[t].get(a)) continue;
            for (int b : colours) {
                if (classAt[group][b] >= 0 || input.blocked[t].get(b)) continue;
                if (recolour(t, a, b)) {
                    assign(id, b, id);
                    return true;
                }
            }
        }
        return false; // Teacher blocks can invalidate swaps. The exact CSP can still succeed.
    }

    private boolean recolour(int start, int a, int b) {
        List<Move> moves = new ArrayList<>();
        int edge = teacherAt[start][b];
        boolean fromTeacher = true;
        while (edge >= 0) {
            budget.check();
            var row = input.rows.get(edge);
            int from = fromTeacher ? b : a, to = fromTeacher ? a : b;
            if (input.blocked[row.teacher()].get(to)) return false;
            moves.add(new Move(edge, from, to));
            edge = fromTeacher ? classAt[row.group()][to] : teacherAt[row.teacher()][to];
            fromTeacher = !fromTeacher;
        }
        for (var move : moves) {
            budget.check();
            assign(move.row(), move.from(), -1);
        }
        for (var move : moves) {
            budget.check();
            assign(move.row(), move.to(), move.row());
        }
        return true;
    }

    private void assign(int rowIndex, int slot, int value) {
        var row = input.rows.get(rowIndex);
        teacherAt[row.teacher()][slot] = classAt[row.group()][slot] = value;
    }

    private static int[][] empty(int n, int slots) {
        int[][] result = new int[n][slots];
        for (int[] times : result) Arrays.fill(times, -1);
        return result;
    }

    private static int[] sequence(int size) {
        int[] result = new int[size];
        Arrays.setAll(result, i -> i);
        return result;
    }

    private void shuffle(int[] indices) {
        for (int i = indices.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = indices[i];
            indices[i] = indices[j];
            indices[j] = tmp;
        }
    }
}
