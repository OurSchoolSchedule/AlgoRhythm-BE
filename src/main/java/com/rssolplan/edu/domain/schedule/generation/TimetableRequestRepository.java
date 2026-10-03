package com.rssolplan.edu.domain.schedule.generation;

import com.rssolplan.edu.domain.schedule.generation.entity.TimetableRequest;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableRequest.TimetableRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimetableRequestRepository extends JpaRepository<TimetableRequest, Long> {

    List<TimetableRequest> findBySchool_Id(Long schoolId);

    Optional<TimetableRequest> findBySchool_IdAndStatus(Long schoolId, TimetableRequestStatus status);
}
