package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;
import static com.rssolplan.edu.domain.schedule.candidates.TimetableSearchTest.*;

import static org.assertj.core.api.Assertions.*;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.loadimport.LoadWorkbookParser.Demand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.TimeUnit;

@EnabledIfEnvironmentVariable(named = "M2_FULL_BENCHMARK", matches = "1")
class CandidatePerformanceTest {
    @Test
    void representativeDenseSchoolWeeks() {
        List<Slot> slots = new ArrayList<>();
        for (var d :
                List.of(DayOfWeek.MON, DayOfWeek.TUE, DayOfWeek.WED, DayOfWeek.THU, DayOfWeek.FRI))
            for (int i = 1; i <= 7; i++) slots.add(new Slot(d, i));
        for (int classes : List.of(10, 30, 60)) {
            var demands = new ArrayList<Demand>();
            for (int c = 0; c < classes; c++)
                for (int subject = 0; subject < 7; subject++)
                    demands.add(demand((c + subject) % classes, subject, c, 5));
            var p = new TimetableProblem(demands, slots, Map.of(), null);
            List<Long> times = new ArrayList<>();
            for (int run = 0; run < 7; run++) {
                long start = System.nanoTime();
                var runner = new CandidateRunner();
                Response result;
                try {
                    result = runner.generate(LOAD, () -> p);
                } finally {
                    runner.close();
                }
                times.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                assertThat(result.candidates()).hasSize(4);
                result.candidates().forEach(c -> checkAssignments(p, c));
            }
            Collections.sort(times);
            System.out.printf(
                    "BENCH classes=%d lessons=%d trials=7 p50Ms=%d p95Ms=%d%n",
                    classes, p.lessons, times.get(3), times.get(6));
        }
    }

    @Test
    void wishesAndUnavailabilityOnThirtyClasses() {
        List<Slot> slots = new ArrayList<>();
        for (var day :
                List.of(DayOfWeek.MON, DayOfWeek.TUE, DayOfWeek.WED, DayOfWeek.THU, DayOfWeek.FRI))
            for (int period = 1; period <= 7; period++) slots.add(new Slot(day, period));
        List<Demand> rows = new ArrayList<>();
        List<Preference> wishes = new ArrayList<>();
        Map<String, Set<Slot>> blocks = new HashMap<>();
        for (int c = 0; c < 30; c++) {
            for (int subject = 0; subject < 6; subject++)
                rows.add(demand((c + subject) % 30, subject, c, 4));
            wishes.add(
                    new Preference(
                            "" + c, Kind.WANT, List.of(slots.get(c % 7), slots.get(7 + c % 7))));
            wishes.add(new Preference("" + c, Kind.AVOID, List.of(slots.get(21 + c % 7))));
            blocks.put("" + c, Set.of(slots.get(28 + c % 7)));
        }
        var p = new TimetableProblem(rows, slots, blocks, wishes);
        var runner = new CandidateRunner(java.time.Duration.ofSeconds(3));
        try {
            var r = runner.generate(LOAD, () -> p);
            assertThat(r.candidates()).hasSize(4);
            r.candidates().forEach(c -> checkAssignments(p, c));
            var score = r.candidates().get(0).score();
            System.out.printf(
                    "BENCH mixed classes=30 lessons=720 elapsedMs=%d wants=%d/30 avoids=%d/30"
                        + " stop=%s%n",
                    r.elapsedMs(), score.wantsMet(), score.avoidsMet(), r.stopReason());
        } finally {
            runner.close();
        }
    }

    @Test
    void productionDeadlineReturnsBestFourUnderCompetingWishes() {
        var runner = new CandidateRunner();
        var p = CandidateRunnerTest.competingWishes();
        long start = System.nanoTime();
        try {
            var r = runner.generate(LOAD, () -> p);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(r.stopReason()).isEqualTo(Stop.TIME_LIMIT);
            assertThat(ms).isBetween(59000L, 60000L);
            assertThat(r.candidates()).hasSize(4);
            r.candidates().forEach(c -> checkAssignments(p, c));
            System.out.printf(
                    "BENCH productionDeadline elapsedMs=%d candidates=%d topWants=%d%n",
                    ms, r.candidates().size(), r.candidates().get(0).score().wantsMet());
        } finally {
            runner.close();
        }
    }
}
