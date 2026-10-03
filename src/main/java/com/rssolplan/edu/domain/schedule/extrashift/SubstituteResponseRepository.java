package com.rssolplan.edu.domain.schedule.extrashift;

import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteResponse;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteResponse.WorkerAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubstituteResponseRepository extends JpaRepository<SubstituteResponse, Long> {

    List<SubstituteResponse> findBySubstituteRequest_Id(Long substituteRequestId);

    /**
     * Returns the response for a request and candidate membership, or empty when none exists.
     *
     * @param candidateId SchoolUser membership ID, rather than user ID
     * @throws org.springframework.dao.IncorrectResultSizeDataAccessException if multiple responses match
     */
    Optional<SubstituteResponse> findBySubstituteRequest_IdAndCandidate_Id(Long substituteRequestId, Long candidateId);

    List<SubstituteResponse> findBySubstituteRequest_IdAndWorkerAction(Long substituteRequestId, WorkerAction action);
}
