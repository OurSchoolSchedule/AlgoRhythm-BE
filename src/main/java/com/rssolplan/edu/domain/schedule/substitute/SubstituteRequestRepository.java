package com.rssolplan.edu.domain.schedule.substitute;

import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteRequest.SubstituteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubstituteRequestRepository extends JpaRepository<SubstituteRequest, Long> {

    List<SubstituteRequest> findBySchool_Id(Long schoolId);

    List<SubstituteRequest> findBySchool_IdAndStatus(Long schoolId, SubstituteStatus status);

    List<SubstituteRequest> findByOwner_Id(Long ownerId);

    List<SubstituteRequest> findByTimetable_Id(Long timetableId);
}
