package com.rssolplan.edu.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Returns notifications newest first with their school loaded, retaining notifications
     * whose school is null.
     */
    @Query("""
        select n
        from Notification n
        left join fetch n.school
        where n.userId = :userId
        order by n.createdAt desc
    """)
    List<Notification> findByUserIdWithSchool(Long userId);

    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
}
