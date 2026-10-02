package com.rssolplan.edu.domain.schedule.extrashift.dto;

import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteResponse;

public record SubstituteApprovalDetail(
        Long requestId,
        Long responseId,
        String requestStatus,
        String teacherAction,
        String managerApproval
) {
    public static SubstituteApprovalDetail from(SubstituteRequest req, SubstituteResponse resp) {
        return new SubstituteApprovalDetail(
                req.getId(),
                resp.getId(),
                req.getStatus().name(),
                resp.getWorkerAction().name(),
                resp.getManagerApproval().name()
        );
    }
}
