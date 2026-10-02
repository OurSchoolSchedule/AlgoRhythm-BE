package com.rssolplan.edu.domain.schedule.shiftswap;

import com.rssolplan.edu.domain.schedule.shiftswap.TimetableSwapRequest.SwapStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TimetableSwapRequestRepository extends JpaRepository<TimetableSwapRequest, Long> {

    List<TimetableSwapRequest> findBySchool_Id(Long schoolId);

    List<TimetableSwapRequest> findByRequester_Id(Long requesterId);

    List<TimetableSwapRequest> findByReceiver_Id(Long receiverId);

    List<TimetableSwapRequest> findBySchool_IdAndStatus(Long schoolId, SwapStatus status);

    List<TimetableSwapRequest> findByRequester_IdOrReceiver_Id(Long requesterId, Long receiverId);
}
