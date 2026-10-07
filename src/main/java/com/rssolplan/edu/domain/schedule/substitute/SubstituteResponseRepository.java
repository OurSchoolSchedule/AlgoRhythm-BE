package com.rssolplan.edu.domain.schedule.substitute;

import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteResponse;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteResponse.WorkerAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubstituteResponseRepository extends JpaRepository<SubstituteResponse, Long> {

    List<SubstituteResponse> findBySubstituteRequest_Id(Long substituteRequestId);

    Optional<SubstituteResponse> findBySubstituteRequest_IdAndCandidate_Id(Long substituteRequestId, Long candidateId);

    List<SubstituteResponse> findBySubstituteRequest_IdAndWorkerAction(Long substituteRequestId, WorkerAction action);
}
