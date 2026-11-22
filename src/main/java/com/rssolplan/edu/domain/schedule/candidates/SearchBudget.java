package com.rssolplan.edu.domain.schedule.candidates;

import java.time.Duration;
import java.util.function.LongSupplier;

final class SearchBudget {
    static final class Expired extends RuntimeException {
        Expired() {
            super(null, null, false, false);
        }
    }

    private final LongSupplier clock;
    private final long start, limit;

    SearchBudget(Duration duration, LongSupplier clock) {
        this.clock = clock;
        this.start = clock.getAsLong();
        this.limit = duration.toNanos();
    }

    long elapsed() {
        return clock.getAsLong() - start;
    }

    long remaining() {
        return Math.max(0, limit - elapsed());
    }

    void check() {
        if (remaining() == 0 || Thread.currentThread().isInterrupted()) throw new Expired();
    }
}
