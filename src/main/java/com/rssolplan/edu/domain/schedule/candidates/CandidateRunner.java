package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Service
final class CandidateRunner {
    private static final Duration LIMIT = Duration.ofSeconds(60);
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    0,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "timetable-candidates");
                        t.setDaemon(true);
                        return t;
                    });
    private final Duration limit;

    CandidateRunner() {
        this(LIMIT);
    }

    CandidateRunner(Duration limit) {
        if (limit.isZero() || limit.isNegative() || limit.compareTo(LIMIT) > 0)
            throw new IllegalArgumentException("편성 시간 제한");
        this.limit = limit;
    }

    Response generate(UUID loadId, Supplier<TimetableProblem> snapshot) {
        return generate(loadId, snapshot, limit);
    }

    Response generate(UUID loadId, Supplier<TimetableProblem> snapshot, Duration remaining) {
        Duration allowed = remaining.compareTo(limit) < 0 ? remaining : limit;
        if (allowed.isNegative() || allowed.isZero())
            return new Response(
                    loadId,
                    Status.TIME_LIMIT_NO_SOLUTION,
                    Stop.TIME_LIMIT,
                    null,
                    0,
                    java.util.List.of());
        // Leave a small margin for waking the caller and returning the last published snapshot.
        long margin = Math.min(TimeUnit.MILLISECONDS.toNanos(250), allowed.toNanos() / 100);
        var budget = new SearchBudget(allowed.minusNanos(margin), System::nanoTime);
        var ranking = new CandidateRanking();
        Future<Outcome> task =
                workers.submit(
                        () -> {
                            budget.check();
                            var input = snapshot.get();
                            budget.check();
                            if (input.capacityFailure != null)
                                return new Outcome(Stop.CAPACITY, input.capacityFailure);
                            Stop stop = new TimetableSearch(input, budget, ranking).run();
                            return new Outcome(stop, null);
                        });
        Outcome outcome;
        try {
            outcome = task.get(budget.remaining(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            outcome = new Outcome(Stop.TIME_LIMIT, null);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SearchBudget.Expired)
                outcome = new Outcome(Stop.TIME_LIMIT, null);
            else if (e.getCause() instanceof RuntimeException failure) throw failure;
            else throw new RuntimeException("시간표 생성 워커 오류", e.getCause());
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("요청이 중단되었습니다.", e);
        }
        var best = ranking.best;
        Status status =
                !best.isEmpty()
                        ? Status.READY
                        : outcome.stop() == Stop.TIME_LIMIT
                                ? Status.TIME_LIMIT_NO_SOLUTION
                                : Status.INFEASIBLE;
        return new Response(
                loadId,
                status,
                outcome.stop(),
                outcome.detail(),
                TimeUnit.NANOSECONDS.toMillis(budget.elapsed()),
                best);
    }

    private record Outcome(Stop stop, String detail) {}

    @PreDestroy
    void close() {
        workers.shutdownNow();
    }
}
