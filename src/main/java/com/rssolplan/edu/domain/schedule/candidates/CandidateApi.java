package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.schedule.DayOfWeek;

import java.util.*;

public final class CandidateApi {
    private CandidateApi() {}

    public record Slot(DayOfWeek day, int period) implements Comparable<Slot> {
        @Override
        public int compareTo(Slot other) {
            int byDay = day.compareTo(other.day);
            return byDay != 0 ? byDay : Integer.compare(period, other.period);
        }
    }

    public enum Kind {
        WANT,
        AVOID
    }

    public record Preference(String teacherId, Kind kind, List<Slot> anyOf) {}

    public enum Objective {
        BALANCED,
        MIN_TEACHER_GAPS,
        PREFER_WANTS
    }

    public record Request(
            UUID validatedLoadId,
            List<Slot> teachingSlots,
            List<Preference> preferences,
            Objective objective) {
        public Request {
            if (objective == null) objective = Objective.BALANCED;
        }

        public Request(
                UUID validatedLoadId, List<Slot> teachingSlots, List<Preference> preferences) {
            this(validatedLoadId, teachingSlots, preferences, Objective.BALANCED);
        }
    }

    public record Placement(String teacherId, String subjectId, String classId, Slot slot) {}

    public record Score(
            int wantsMet,
            int wantsTotal,
            int teachersWithNoWantMet,
            int avoidsMet,
            int avoidsTotal,
            Integer teacherGapPeriods) {}

    public record Candidate(String candidateId, Score score, List<Placement> placements) {}

    public enum Stop {
        SEARCH_EXHAUSTED,
        TOP_FOUR_AT_BOUND,
        CAPACITY,
        TIME_LIMIT
    }

    public enum Status {
        READY,
        INFEASIBLE,
        TIME_LIMIT_NO_SOLUTION
    }

    public record Response(
            UUID validatedLoadId,
            Status status,
            Stop stopReason,
            String detail,
            long elapsedMs,
            List<Candidate> candidates,
            UUID jobId,
            Long expectedPublishRev) {
        Response(
                UUID id,
                Status status,
                Stop stop,
                String detail,
                long elapsedMs,
                List<Candidate> candidates) {
            this(id, status, stop, detail, elapsedMs, candidates, null, null);
        }
    }
}
