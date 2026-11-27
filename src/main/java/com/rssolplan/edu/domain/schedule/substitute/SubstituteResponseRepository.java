package com.rssolplan.edu.domain.schedule.substitute;

import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteResponse;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteResponse.WorkerAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubstituteResponseRepository extends JpaRepository<SubstituteResponse, Long> {

    List<SubstituteResponse> findBySubstituteRequest_Id(Long substituteRequestId);

    boolean existsBySubstituteRequest_IdAndCandidate_IdIn(Long substituteRequestId, List<Long> candidateIds);

    List<SubstituteResponse> findBySubstituteRequest_IdAndWorkerAction(Long substituteRequestId, WorkerAction action);
}
