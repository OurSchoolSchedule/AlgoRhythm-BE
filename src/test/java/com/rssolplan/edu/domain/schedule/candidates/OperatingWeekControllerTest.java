package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.DayOfWeek.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperatingWeekControllerTest {
    @Test
    void returnsThePublishedGradeWindowWithinTheActualRegisteredSlots() {
        var calendar = new PublishedOperatingHours.Calendar(
                List.of(new CandidateApi.Slot(MON, 1), new CandidateApi.Slot(MON, 3), new CandidateApi.Slot(TUE, 2)),
                List.of(new CandidateApi.GradeLastPeriod(1, Map.of(MON, 1)),
                        new CandidateApi.GradeLastPeriod(2, Map.of(MON, 3))),
                Map.of("101", 1, "201", 2), Map.of(1, 41L, 2, 42L, 3, 43L));
        var board = OperatingWeekController.day(LocalDate.of(2026, 6, 8), 7, calendar);
        assertThat(board.periods()).containsExactly(1, 3);
        assertThat(board.gradePeriods()).containsEntry(1, List.of(1)).containsEntry(2, List.of(1, 3));
        assertThat(board.classGrades()).containsEntry("101", 1);
        var legacy = new PublishedOperatingHours.Calendar(calendar.teachingSlots(), null, null, calendar.periodIds());
        var common = OperatingWeekController.day(LocalDate.of(2026, 6, 9), 6, legacy);
        assertThat(common.periods()).containsExactly(2);
        assertThat(common.gradePeriods()).isEmpty();
    }
}
