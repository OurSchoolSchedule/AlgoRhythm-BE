package com.rssolplan.edu.domain.schedule.operations;

import com.rssolplan.edu.domain.schedule.candidates.CandidateApi.GradeLastPeriod;
import com.rssolplan.edu.domain.schedule.candidates.CandidateApi.Slot;
import com.rssolplan.edu.domain.schedule.candidates.PublishedOperatingHours;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.rssolplan.edu.domain.schedule.DayOfWeek.TUE;
import static com.rssolplan.edu.domain.schedule.operations.ChangeService.Kind.SWAP;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SwapTeachingHoursTest {
    @Test
    void rejectsEitherDirectionBeforeWritingWhenOneClassWouldFinishTooLate() {
        var date = LocalDate.of(2036, 3, 4);
        var first = lesson(80, 11, 50, 610, date);
        var second = lesson(81, 12, 51, 620, date);
        var hours = mock(PublishedOperatingHours.class);
        when(hours.forVersion(4)).thenReturn(Optional.of(new PublishedOperatingHours.Calendar(
                List.of(new Slot(TUE, 1), new Slot(TUE, 2)),
                List.of(new GradeLastPeriod(1, Map.of(TUE, 1)), new GradeLastPeriod(2, Map.of(TUE, 2))),
                Map.of("11", 1, "12", 2), Map.of(1, 610L, 2, 620L))));
        var db = mock(JdbcTemplate.class);
        var lessons = spy(new DatedLessons(db, mock(ScheduleAccess.class), mock(ScheduleJson.class), hours));
        doReturn(List.of(50L, 51L)).when(lessons).teachingMembers(1);
        doReturn(List.of(first, second)).when(lessons).day(3, date);
        doReturn(Set.of()).when(lessons).unavailable(1, date);
        doReturn(first).when(lessons).get(3, first.id());
        doReturn(second).when(lessons).get(3, second.id());

        for (var source : List.of(first, second)) {
            var target = source == first ? second : first;
            assertThat(lessons.candidates(1, source, SWAP, date)).doesNotContain(target.id());
            assertThatThrownBy(() -> lessons.apply(1, 3, source.id(), 0, target.id(), 0L, target.actual().teacherId()))
                    .isInstanceOfSatisfying(ScheduleConflict.class, e -> assertThat(e.code).isEqualTo("SLOT_CONFLICT"));
        }
        verifyNoInteractions(db);
        // A legacy version without a receipt retains the existing occupancy/availability rules.
        when(hours.forVersion(4)).thenReturn(Optional.empty());
        assertThat(lessons.candidates(1, first, SWAP, date)).containsExactly(second.id());
    }

    private static DatedLessons.Lesson lesson(long id, long room, long teacher, long period, LocalDate date) {
        var assignment = new DatedLessons.Assignment(teacher, period);
        return new DatedLessons.Lesson(id, id + 20, 3, 4, date, date, room, 17, assignment, assignment, 0);
    }
}
