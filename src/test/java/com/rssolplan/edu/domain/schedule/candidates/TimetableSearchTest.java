package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import static org.assertj.core.api.Assertions.*;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.loadimport.LoadWorkbookParser.Demand;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.stream.*;

class TimetableSearchTest {
    static final UUID LOAD = UUID.fromString("375d0754-c375-42c6-b1f0-f630a9693e37");

    static Slot mon(int n) {
        return new Slot(DayOfWeek.MON, n);
    }

    static Demand demand(int teacher, int subject, int group, int hours) {
        return new Demand("" + teacher, "" + subject, "" + group, hours, "C2");
    }

    static Response solve(TimetableProblem problem) {
        var runner = new CandidateRunner(Duration.ofSeconds(5));
        try {
            return runner.generate(LOAD, () -> problem);
        } finally {
            runner.close();
        }
    }

    static void checkAssignments(TimetableProblem p, Candidate candidate) {
        var expected = new HashMap<List<String>, Integer>();
        for (var r : p.rows) {
            var d = r.demand();
            expected.put(List.of(d.teacherId(), d.subjectId(), d.classId()), d.weeklyPeriods());
        }
        var actual = new HashMap<List<String>, Integer>();
        Set<List<Object>> teachers = new HashSet<>(), groups = new HashSet<>();
        for (var a : candidate.placements()) {
            int s = p.slots.indexOf(a.slot());
            assertThat(s).isNotNegative();
            assertThat(teachers.add(List.of(a.teacherId(), a.slot()))).isTrue();
            assertThat(groups.add(List.of(a.classId(), a.slot()))).isTrue();
            actual.merge(List.of(a.teacherId(), a.subjectId(), a.classId()), 1, Integer::sum);
        }
        for (var r : p.rows)
            for (int s = p.blocked[r.teacher()].nextSetBit(0);
                    s >= 0;
                    s = p.blocked[r.teacher()].nextSetBit(s + 1))
                assertThat(teachers)
                        .doesNotContain(List.of(r.demand().teacherId(), p.slots.get(s)));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void exactHoursAndNoDoubleBookingAcrossSubjectsTeachersAndClasses() {
        var p =
                new TimetableProblem(
                        List.of(
                                demand(1, 10, 1, 2),
                                demand(2, 20, 1, 1),
                                demand(1, 30, 2, 1),
                                demand(2, 30, 2, 1)),
                        List.of(mon(1), mon(2), mon(3), mon(4)),
                        Map.of("1", Set.of(mon(4))),
                        List.of());
        var r = solve(p);
        assertThat(r.status()).isEqualTo(Status.READY);
        assertThat(r.candidates())
                .hasSize(4)
                .extracting(Candidate::candidateId)
                .doesNotHaveDuplicates();
        r.candidates().forEach(c -> checkAssignments(p, c));
    }

    @Test
    void identicalHoursAreIndistinguishableAndOneCandidateIsReturned() {
        var p =
                new TimetableProblem(
                        List.of(demand(1, 10, 1, 2)), List.of(mon(1), mon(2)), Map.of(), null);
        var r = solve(p);
        assertThat(r.stopReason()).isEqualTo(Stop.SEARCH_EXHAUSTED);
        assertThat(r.candidates()).singleElement().satisfies(c -> checkAssignments(p, c));
    }

    @Test
    void exhaustedSearchDetectsHallConflictEvenWhenIndividualCapacitiesPass() {
        var p =
                new TimetableProblem(
                        List.of(demand(1, 1, 1, 1), demand(2, 1, 1, 1)),
                        List.of(mon(1), mon(2)),
                        Map.of("1", Set.of(mon(2)), "2", Set.of(mon(2))),
                        null);
        var r = solve(p);
        assertThat(r.status()).isEqualTo(Status.INFEASIBLE);
        assertThat(r.stopReason()).isEqualTo(Stop.SEARCH_EXHAUSTED);
        assertThat(r.candidates()).isEmpty();
    }

    @Test
    void provenCapacityShortageIdentifiesTheSourceCell() {
        var p =
                new TimetableProblem(
                        List.of(demand(1, 1, 1, 65535)), List.of(mon(1)), Map.of(), null);
        var r = solve(p);
        assertThat(r.stopReason()).isEqualTo(Stop.CAPACITY);
        assertThat(r.detail()).contains("C2", "교사");
        assertThat(r.candidates()).isEmpty();
    }

    @Test
    void comparesBestFourWithIndependentBruteForceIncludingPreferenceFairnessAndAvoids() {
        var slots = List.of(mon(1), mon(2), mon(3), mon(4));
        var requests =
                List.of(
                        new Preference("1", Kind.WANT, List.of(mon(1), mon(2))),
                        new Preference("1", Kind.WANT, List.of(mon(2), mon(1))), // same ANY group
                        new Preference("1", Kind.WANT, List.of(mon(2))),
                        new Preference("2", Kind.WANT, List.of(mon(2))),
                        new Preference("1", Kind.AVOID, List.of(mon(4))),
                        new Preference("2", Kind.AVOID, List.of(mon(1))));
        var p =
                new TimetableProblem(
                        List.of(demand(1, 1, 1, 1), demand(2, 2, 1, 1), demand(3, 4, 2, 1)),
                        slots,
                        Map.of("3", Set.of(mon(1), mon(2), mon(3))),
                        requests);
        var expect = new ArrayList<Score>();
        for (int a = 1; a <= 4; a++)
            for (int b = 1; b <= 4; b++)
                if (a != b) {
                    int w1 = (a <= 2 ? 1 : 0) + (a == 2 ? 1 : 0), w2 = b == 2 ? 1 : 0;
                    expect.add(
                            new Score(
                                    w1 + w2,
                                    3,
                                    (w1 == 0 ? 1 : 0) + (w2 == 0 ? 1 : 0),
                                    (a != 4 ? 1 : 0) + (b != 1 ? 1 : 0),
                                    2,
                                    0));
                }
        expect.sort(CandidateRanking.QUALITY);
        var result = solve(p);
        assertThat(result.stopReason()).isEqualTo(Stop.SEARCH_EXHAUSTED);
        assertThat(result.candidates())
                .extracting(Candidate::score)
                .containsExactlyElementsOf(expect.subList(0, 4));
        result.candidates().forEach(c -> checkAssignments(p, c));
    }

    @Test
    void sameLoadHasThreeDifferentBestLayoutsAccordingToObjective() {
        var slots = List.of(mon(1), mon(2), mon(3), mon(4));
        var demands = List.of(demand(1, 10, 1, 2), demand(2, 20, 1, 1), demand(3, 30, 1, 1));
        var preferences =
                List.of(
                        new Preference("3", Kind.WANT, List.of(mon(3))),
                        new Preference("1", Kind.WANT, List.of(mon(1))),
                        new Preference("1", Kind.WANT, List.of(mon(4))),
                        new Preference("2", Kind.WANT, List.of(mon(1))),
                        new Preference("1", Kind.AVOID, List.of(mon(2))),
                        new Preference("3", Kind.AVOID, List.of(mon(4))),
                        new Preference("2", Kind.AVOID, List.of(mon(4))));
        var expected =
                Map.of(
                        Objective.BALANCED, List.of("2", "1", "3", "1"),
                        Objective.MIN_TEACHER_GAPS, List.of("2", "3", "1", "1"),
                        Objective.PREFER_WANTS, List.of("1", "2", "3", "1"));
        for (var objective : Objective.values()) {
            var problem = new TimetableProblem(demands, slots, Map.of(), preferences, objective);
            var result = solve(problem);
            assertThat(result.candidates())
                    .hasSize(4)
                    .allSatisfy(c -> checkAssignments(problem, c));
            var first = result.candidates().get(0);
            var teachers =
                    first.placements().stream()
                            .sorted(Comparator.comparing(Placement::slot))
                            .map(Placement::teacherId)
                            .toList();
            assertThat(teachers).isEqualTo(expected.get(objective));
            assertThat(first.score().teacherGapPeriods())
                    .isEqualTo(teachers.lastIndexOf("1") - teachers.indexOf("1") - 1);
        }
    }

    @Test
    void gapObjectiveCannotStopAtFourPreferencePerfectCandidatesWithGaps() {
        var p =
                new TimetableProblem(
                        List.of(demand(1, 10, 1, 2)),
                        List.of(mon(1), mon(3), mon(5), mon(7), new Slot(DayOfWeek.TUE, 2)),
                        Map.of(),
                        List.of(),
                        Objective.MIN_TEACHER_GAPS);
        var result = solve(p);
        assertThat(result.candidates())
                .hasSize(4)
                .allSatisfy(c -> assertThat(c.score().teacherGapPeriods()).isZero());
    }

    @Test
    void verifierRejectsPartialClashingAndBlockedLayouts() {
        var p =
                new TimetableProblem(
                        List.of(demand(1, 1, 1, 1), demand(1, 2, 2, 1)),
                        List.of(mon(1), mon(2), mon(3)),
                        Map.of("1", Set.of(mon(3))),
                        null);
        var budget = new SearchBudget(Duration.ofSeconds(2), System::nanoTime);
        var ranking = new CandidateRanking();
        for (long[] masks :
                List.of(
                        new long[] {1, 0},
                        new long[] {1, 1},
                        new long[] {1, 4},
                        new long[] {1, 8})) {
            var layout =
                    Arrays.stream(masks)
                            .mapToObj(mask -> BitSet.valueOf(new long[] {mask}))
                            .toArray(BitSet[]::new);
            assertThatThrownBy(() -> ranking.offer(p, layout, budget))
                    .isInstanceOf(CandidateRanking.InvalidLayout.class);
        }
        assertThat(ranking.best).isEmpty();
    }

    @Test
    void seededSmallGraphsAgreeWithIndependentEnumerationOnFeasibilityAndNumberOfCandidates() {
        var rng = new Random(95);
        var slots = List.of(mon(1), mon(2), mon(3));
        for (int k = 0; k < 35; k++) {
            var demands = new ArrayList<Demand>();
            for (int i = 0; i < 4; i++) demands.add(demand(rng.nextInt(3), i, rng.nextInt(3), 1));
            var forbidden = Map.of("1", Set.of(slots.get(rng.nextInt(3))));
            var p = new TimetableProblem(demands, slots, forbidden, null);
            int count = 0;
            for (int assignment = 0; assignment < 81; assignment++) {
                Set<List<Object>> t = new HashSet<>(), c = new HashSet<>();
                boolean ok = true;
                int n = assignment;
                for (var d : demands) {
                    var s = slots.get(n % 3);
                    n /= 3;
                    ok &=
                            !forbidden.getOrDefault(d.teacherId(), Set.of()).contains(s)
                                    && t.add(List.of(d.teacherId(), s))
                                    && c.add(List.of(d.classId(), s));
                }
                if (ok) count++;
            }
            var r = solve(p);
            assertThat(r.candidates()).hasSize(Math.min(4, count));
            r.candidates().forEach(candidate -> checkAssignments(p, candidate));
        }
    }
}
