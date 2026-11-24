package com.rssolplan.edu.domain.schedule.operations;

public class ScheduleConflict extends RuntimeException {
    public final String code;
    public final Long currentRev;
    public final String activeJobId;

    public ScheduleConflict(String code, Long currentRev, String message) {
        this(code, currentRev, message, null);
    }

    public ScheduleConflict(String code, Long currentRev, String message, String activeJobId) {
        super(message);
        this.code = code;
        this.currentRev = currentRev;
        this.activeJobId = activeJobId;
    }
}
