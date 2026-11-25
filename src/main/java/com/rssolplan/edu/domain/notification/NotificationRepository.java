package com.rssolplan.edu.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // School notifications follow current employment. Personal notifications have no school.
    String VISIBLE_TO_USER = """
        n.userId = :userId and (n.school is null or exists (
            select m.id from SchoolUser m where m.user.id = :userId and m.school = n.school
            and m.employmentStatus = HIRED
        ))
        """;

    @Query("select n from Notification n left join fetch n.school where " + VISIBLE_TO_USER
            + " order by n.createdAt desc")
    List<Notification> findVisibleForUser(@Param("userId") Long userId);

    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("update Notification n set n.isRead = true where n.isRead = false and " + VISIBLE_TO_USER)
    void markVisibleAsRead(@Param("userId") Long userId);
}
