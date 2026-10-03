package com.rssolplan.edu.domain.school;

import com.rssolplan.edu.domain.school.SchoolUser.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SchoolUserRepository extends JpaRepository<SchoolUser, Long> {

    List<SchoolUser> findByUser_Id(Long userId);

    List<SchoolUser> findByUser_IdAndPosition(Long userId, Position position);

    Optional<SchoolUser> findByUser_IdAndSchool_Id(Long userId, Long schoolId);

    boolean existsByUser_IdAndSchool_Id(Long userId, Long schoolId);

    Optional<SchoolUser> findFirstByUser_IdOrderByCreatedAtAsc(Long userId);

    List<SchoolUser> findBySchool_Id(Long schoolId);

    List<SchoolUser> findBySchool_IdAndPosition(Long schoolId, Position position);

    @Query("""
        SELECT su.id, u.username
        FROM SchoolUser su
        JOIN su.user u
        WHERE su.school.id = :schoolId
    """)
    List<Object[]> findSchoolUserIdAndUsernameBySchoolId(@Param("schoolId") Long schoolId);

    @Query("""
        SELECT su FROM SchoolUser su
        WHERE su.user.id = :userId AND su.school.id = :schoolId
    """)
    Optional<SchoolUser> findByUserIdAndSchoolId(@Param("userId") Long userId,
                                                 @Param("schoolId") Long schoolId);
}
