package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Single writer. The caller awaiting the deadline only reads complete immutable snapshots. */
final class CandidateRanking {
    static final Comparator<Score> QUALITY =
            Comparator.comparingInt(Score::wantsMet)
                    .reversed()
                    .thenComparingInt(Score::teachersWithNoWantMet)
                    .thenComparing(Comparator.comparingInt(Score::avoidsMet).reversed());
    static final Comparator<Candidate> ORDER =
            Comparator.comparing(Candidate::score, QUALITY).thenComparing(Candidate::candidateId);
    volatile List<Candidate> best = List.of();

    boolean fourAtBound() {
        if (best.size() != 4) return false;
        Score worst = best.get(3).score();
        return worst.wantsMet() == worst.wantsTotal() && worst.avoidsMet() == worst.avoidsTotal();
    }

    // Rebuild resource occupancy and each exact demand count independently of the search state.
    void offer(TimetableProblem input, BitSet[] layout, SearchBudget budget) {
        budget.check();
        BitSet[] teacher = emptySets(input.teacherHours.length),
                groups = emptySets(input.classHours.length);
        if (layout.length != input.rows.size()) throw new InvalidLayout();
        for (int i = 0; i < layout.length; i++) {
            budget.check();
            var row = input.rows.get(i);
            BitSet times = layout[i];
            if (times.length() > input.slots.size()
                    || times.cardinality() != row.demand().weeklyPeriods()
                    || times.intersects(input.blocked[row.teacher()])
                    || times.intersects(teacher[row.teacher()])
                    || times.intersects(groups[row.group()])) throw new InvalidLayout();
            teacher[row.teacher()].or(times);
            groups[row.group()].or(times);
        }
        int wants = 0, matched = 0, avoids = 0, avoided = 0;
        BitSet submitters = new BitSet(), satisfied = new BitSet();
        for (var wish : input.wishes) {
            budget.check();
            BitSet busy = teacher[wish.teacher()];
            if (wish.kind() == Kind.WANT) {
                wants++;
                submitters.set(wish.teacher());
                if (wish.anyOf().stream().anyMatch(busy::get)) {
                    matched++;
                    satisfied.set(wish.teacher());
                }
            } else {
                avoids++;
                if (wish.anyOf().stream().anyMatch(slot -> !busy.get(slot))) avoided++;
            }
        }
        var score =
                new Score(
                        matched,
                        wants,
                        submitters.cardinality() - satisfied.cardinality(),
                        avoided,
                        avoids);
        if (best.size() == 4 && QUALITY.compare(score, best.get(3).score()) > 0) return;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        List<Placement> placements = new ArrayList<>(input.lessons);
        for (int i = 0; i < layout.length; i++) {
            budget.check();
            var d = input.rows.get(i).demand();
            digest.update(
                    (d.teacherId() + "/" + d.classId() + "/" + d.subjectId() + ":")
                            .getBytes(StandardCharsets.US_ASCII));
            for (int s = layout[i].nextSetBit(0); s >= 0; s = layout[i].nextSetBit(s + 1)) {
                var slot = input.slots.get(s);
                digest.update(
                        (slot.day() + "/" + slot.period() + ",")
                                .getBytes(StandardCharsets.US_ASCII));
                placements.add(new Placement(d.teacherId(), d.subjectId(), d.classId(), slot));
            }
            digest.update((byte) '\n');
        }
        String id = HexFormat.of().formatHex(digest.digest());
        if (best.stream().anyMatch(c -> c.candidateId().equals(id))) return;
        var updated = new ArrayList<>(best);
        updated.add(new Candidate(id, score, List.copyOf(placements)));
        updated.sort(ORDER);
        if (updated.size() > 4) updated.remove(4);
        budget.check();
        best = List.copyOf(updated);
    }

    static BitSet[] emptySets(int size) {
        var result = new BitSet[size];
        Arrays.setAll(result, i -> new BitSet());
        return result;
    }

    static final class InvalidLayout extends RuntimeException {
        InvalidLayout() {
            super("편성 결과가 검증된 주당 시수 또는 교시의 필수 조건을 위반합니다.");
        }
    }
}
