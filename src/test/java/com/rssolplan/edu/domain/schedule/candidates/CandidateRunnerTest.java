package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;
import static com.rssolplan.edu.domain.schedule.candidates.TimetableSearchTest.*;

import static org.assertj.core.api.Assertions.*;

import com.rssolplan.edu.domain.schedule.loadimport.LoadWorkbookParser.Demand;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

class CandidateRunnerTest {
    static TimetableProblem competingWishes() {
        List<Demand> demands = new ArrayList<>();
        var prefs = new ArrayList<Preference>();
        for (int i = 1; i <= 12; i++) {
            demands.add(demand(i, i, 1, 1));
            prefs.add(new Preference("" + i, Kind.WANT, List.of(mon(1))));
        }
        return new TimetableProblem(
                demands,
                IntStream.rangeClosed(1, 18).mapToObj(TimetableSearchTest::mon).toList(),
                Map.of(),
                prefs);
    }

    @Test
    void productionLimitCannotBeExtended() {
        assertThatThrownBy(() -> new CandidateRunner(Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadlineWithNoIncumbentInterruptsWorkerAndDoesNotReportInfeasible() throws Exception {
        var runner = new CandidateRunner(Duration.ofMillis(100));
        var interrupted = new CountDownLatch(1);
        try {
            var result =
                    runner.generate(
                            LOAD,
                            () -> {
                                try {
                                    new CountDownLatch(1).await();
                                } catch (InterruptedException e) {
                                    interrupted.countDown();
                                }
                                return competingWishes();
                            });
            assertThat(result.status()).isEqualTo(Status.TIME_LIMIT_NO_SOLUTION);
            assertThat(result.stopReason()).isEqualTo(Stop.TIME_LIMIT);
            assertThat(result.candidates()).isEmpty();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            runner.close();
        }
    }

    @Test
    void timeoutRetainsTopValidatedIncumbentsAndWorkerIsReusable() throws Exception {
        var p = competingWishes();
        var runner = new CandidateRunner(Duration.ofMillis(500));
        try {
            var r = runner.generate(LOAD, () -> p);
            assertThat(r.stopReason()).isEqualTo(Stop.TIME_LIMIT);
            assertThat(r.status()).isEqualTo(Status.READY);
            assertThat(r.candidates()).hasSize(4).isSortedAccordingTo(CandidateRanking.ORDER);
            r.candidates().forEach(c -> checkAssignments(p, c));
            var tiny =
                    new TimetableProblem(
                            List.of(demand(1, 1, 1, 1)), List.of(mon(1)), Map.of(), null);
            Response next = null;
            for (int i = 0; i < 100 && next == null; i++) {
                try {
                    next = runner.generate(LOAD, () -> tiny);
                } catch (RejectedExecutionException finishing) {
                    Thread.sleep(5);
                }
            }
            assertThat(next).isNotNull();
            assertThat(next.candidates()).hasSize(1);
        } finally {
            runner.close();
        }
    }

    @Test
    void concurrentRequestIsRejectedWithoutQueueing() throws Exception {
        var runner = new CandidateRunner(Duration.ofSeconds(3));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var first =
                    caller.submit(
                            () ->
                                    runner.generate(
                                            LOAD,
                                            () -> {
                                                entered.countDown();
                                                try {
                                                    release.await();
                                                } catch (InterruptedException e) {
                                                    throw new RuntimeException(e);
                                                }
                                                return new TimetableProblem(
                                                        List.of(demand(1, 1, 1, 1)),
                                                        List.of(mon(1)),
                                                        Map.of(),
                                                        null);
                                            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> runner.generate(LOAD, () -> null))
                    .isInstanceOf(RejectedExecutionException.class);
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(Status.READY);
        } finally {
            release.countDown();
            runner.close();
            caller.shutdownNow();
        }
    }

    @Test
    void innerSearchHonoursMonotonicDeadlineEvenWithoutExternalInterrupt() {
        var now = new AtomicLong();
        var budget = new SearchBudget(Duration.ofNanos(2500), now::incrementAndGet);
        var ranking = new CandidateRanking();
        assertThatThrownBy(() -> new TimetableSearch(competingWishes(), budget, ranking).run())
                .isInstanceOf(SearchBudget.Expired.class);
        assertThat(now.get()).isBetween(2501L, 2502L);
    }
}
