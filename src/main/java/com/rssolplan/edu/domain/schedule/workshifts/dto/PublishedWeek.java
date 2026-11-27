package com.rssolplan.edu.domain.schedule.workshifts.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record PublishedWeek(long termId, LocalDate weekStart, Long teacherId, Long classId,
                            List<Day> days, List<TimetableDto> lessons) {
    public record Day(LocalDate date, boolean inTerm, boolean excluded, boolean published,
                      Map<Integer,Integer> lastPeriodByGrade, Integer lastPeriod,
                      List<Integer> periods, Map<Integer,List<Integer>> gradePeriods) {}
}
