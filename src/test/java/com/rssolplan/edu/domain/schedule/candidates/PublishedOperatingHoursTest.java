package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.schedule.candidates.CandidateApi.GradeLastPeriod;
import com.rssolplan.edu.domain.schedule.candidates.CandidateApi.Slot;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

import static com.rssolplan.edu.domain.schedule.DayOfWeek.*;
import static org.assertj.core.api.Assertions.assertThat;

class PublishedOperatingHoursTest {
    private final LocalDate monday = LocalDate.of(2036, 3, 3);
    private final List<Slot> slots = List.of(new Slot(MON, 1), new Slot(MON, 3), new Slot(TUE, 1));
    private final Map<Integer, Long> bells = Map.of(1, 500L, 3, 700L);
    private final Map<String, Integer> grades = Map.of("30", 1, "40", 2);

    @Test
    void usesTheClassGradeDestinationDayAndFrozenBellId() {
        var first = new GradeLastPeriod(1, Map.of(MON, 1, TUE, 0));
        var second = new GradeLastPeriod(2, Map.of(MON, 3, TUE, 1));
        var calendar = new PublishedOperatingHours.Calendar(slots, List.of(first, second), grades, bells);
        assertThat(calendar.allows(30, monday, 500)).isTrue();
        assertThat(calendar.allows(40, monday, 700)).isTrue();
        assertThat(calendar.allows(30, monday, 700)).isFalse();
        assertThat(calendar.allows(30, monday.plusDays(1), 500)).isFalse();
        assertThat(calendar.allows(40, monday.plusDays(1), 500)).isTrue();
        assertThat(calendar.allows(30, monday, 1)).isFalse();
        assertThat(calendar.allows(999, monday, 500)).isFalse();
    }

    @Test
    void historicalCommonCalendarsKeepTheirExactSlotUnion() {
        var saturday = new Slot(SAT, 3);
        var calendar = new PublishedOperatingHours.Calendar(List.of(slots.get(0), saturday), null, null, bells);
        assertThat(calendar.allows(30, monday, 500)).isTrue();
        assertThat(calendar.allows(40, monday, 700)).isFalse();
        assertThat(calendar.allows(30, monday.plusDays(5), 700)).isTrue();
    }
}
