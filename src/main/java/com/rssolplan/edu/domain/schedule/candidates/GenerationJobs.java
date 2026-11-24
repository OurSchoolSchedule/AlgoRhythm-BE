package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import com.rssolplan.edu.domain.schedule.operations.ScheduleConflict;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
class GenerationJobs {
    private final GenerationStore store;
    private final CandidateInputService inputs;
    private final CandidateRunner runner;
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService dispatch = Executors.newSingleThreadExecutor();
    private final AtomicBoolean active = new AtomicBoolean();

    GenerationStore.Ticket submit(Long user, long term, Request request) {
        long started = System.nanoTime();
        var snapshot = inputs.freeze(user, term, null, request);
        snapshot.problem(); // Validate preferences and bounds before issuing a ticket.
        return store.insert(snapshot, null, remainingMicros(started));
    }

    Response sync(Long user, Long term, Integer year, Request request) {
        long started = System.nanoTime();
        var input = inputs.freeze(user, term, year, request);
        input.problem();
        UUID token = UUID.randomUUID();
        var ticket = store.insert(input, token, remainingMicros(started));
        return execute(ticket.jobId(), token, input, ticket.expectedPublishRev(), false);
    }

    private static long remainingMicros(long started) {
        return Math.max(
                0,
                TimeUnit.SECONDS.toMicros(60)
                        - TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void pump() {
        if (!active.compareAndSet(false, true)) return;
        dispatch.execute(
                () -> {
                    try {
                        store.next()
                                .ifPresent(
                                        id -> {
                                            UUID token = UUID.randomUUID();
                                            if (store.claim(id, token)) {
                                                var job = store.get(id);
                                                execute(
                                                        id,
                                                        token,
                                                        job.snapshot(),
                                                        job.baseRev(),
                                                        true);
                                            }
                                        });
                    } catch (RuntimeException e) {
                        log.warn("Generation dispatch failed", e);
                    } finally {
                        active.set(false);
                    }
                });
    }

    private Response execute(
            UUID id,
            UUID token,
            CandidateInputService.Snapshot input,
            long baseRev,
            boolean background) {
        var heartbeat =
                heartbeats.scheduleAtFixedRate(
                        () -> store.renew(id, token), 3, 3, TimeUnit.SECONDS);
        try {
            long micros = store.remainingMicros(id);
            var response =
                    runner.generate(
                            input.request().validatedLoadId(),
                            input::problem,
                            Duration.ofNanos(Math.max(0, micros) * 1000));
            var saved =
                    new Response(
                            response.validatedLoadId(),
                            response.status(),
                            response.stopReason(),
                            response.detail(),
                            response.elapsedMs(),
                            response.candidates(),
                            id,
                            baseRev);
            if (!store.finish(id, token, saved.status().name(), saved))
                throw new ScheduleConflict("JOB_EXPIRED", null, "작업 소유권이 만료되었습니다.");
            return saved;
        } catch (RejectedExecutionException busy) {
            // A synchronous caller may be using this JVM. Permit a worker to claim it again within
            // its original budget.
            if (background) store.requeue(id, token);
            else store.finish(id, token, "FAILED", null);
            throw busy;
        } catch (RuntimeException e) {
            store.finish(id, token, "FAILED", null);
            throw e;
        } finally {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    void close() {
        dispatch.shutdownNow();
        heartbeats.shutdownNow();
    }
}
