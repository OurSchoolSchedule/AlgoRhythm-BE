package com.rssolplan.edu.domain.schedule.shiftswap;

import com.rssolplan.edu.domain.schedule.shiftswap.TimetableSwapRequest.SwapStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TimetableSwapRequestRepository extends JpaRepository<TimetableSwapRequest, Long> {

    List<TimetableSwapRequest> findBySchool_Id(Long schoolId);

    List<TimetableSwapRequest> findByRequester_Id(Long requesterId);

    List<TimetableSwapRequest> findByReceiver_Id(Long receiverId);

    List<TimetableSwapRequest> findBySchool_IdAndStatus(Long schoolId, SwapStatus status);

    /**
     * Returns swaps with either the supplied requester membership or receiver membership.
     * Both arguments are SchoolUser IDs; pass the same ID to find one member's participation.
     */
    List<TimetableSwapRequest> findByRequester_IdOrReceiver_Id(Long requesterId, Long receiverId);
}
