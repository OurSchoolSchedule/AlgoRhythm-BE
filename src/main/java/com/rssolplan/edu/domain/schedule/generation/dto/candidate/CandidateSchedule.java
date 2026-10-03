package com.rssolplan.edu.domain.schedule.generation.dto.candidate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CandidateSchedule {
    private Long schoolId;
    private List<CandidateShift> shifts = new ArrayList<>();

    private String strategyName;
    private String strategyDescription;

    private int totalShifts;
    private int unassignedCount;
    private double coverageRate;

    public CandidateSchedule(Long schoolId) {
        this.schoolId = schoolId;
    }

    public CandidateSchedule(Long schoolId, String strategyName, String strategyDescription) {
        this.schoolId = schoolId;
        this.strategyName = strategyName;
        this.strategyDescription = strategyDescription;
    }

    public void addShift(CandidateShift shift) {
        this.shifts.add(shift);
    }

    public void calculateMetadata() {
        this.totalShifts = shifts.size();
        this.unassignedCount = (int) shifts.stream()
                .filter(s -> "UNASSIGNED".equals(s.getStatus()))
                .count();
        int assignedCount = totalShifts - unassignedCount;
        this.coverageRate = totalShifts > 0
                ? Math.round((double) assignedCount / totalShifts * 100 * 10) / 10.0
                : 0;
    }
}
