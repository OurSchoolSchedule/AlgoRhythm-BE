package com.rssolplan.edu.domain.schedule.extrashift;

import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.extrashift.entity.SubstituteRequest.SubstituteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubstituteRequestRepository extends JpaRepository<SubstituteRequest, Long> {

    List<SubstituteRequest> findBySchool_Id(Long schoolId);

    List<SubstituteRequest> findBySchool_IdAndStatus(Long schoolId, SubstituteStatus status);

    List<SubstituteRequest> findByOwner_Id(Long ownerId);

    List<SubstituteRequest> findByTimetable_Id(Long timetableId);
}
