package com.rssolplan.edu.domain.schedule.extrashift.dto;

import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteResponse;

import java.time.LocalDateTime;

public record SubstituteResponseDetail(
        Long requestId,
        Long responseId,
        Long candidateSchoolUserId,
        String teacherName,
        String teacherAction,
        String managerApproval,
        LocalDateTime createdAt
) {
    public static SubstituteResponseDetail from(SubstituteResponse resp) {
        return new SubstituteResponseDetail(
                resp.getSubstituteRequest().getId(),
                resp.getId(),
                resp.getCandidate().getId(),
                resp.getCandidate().getUser().getUsername(),
                resp.getWorkerAction().name(),
                resp.getManagerApproval().name(),
                resp.getCreatedAt()
        );
    }
}
