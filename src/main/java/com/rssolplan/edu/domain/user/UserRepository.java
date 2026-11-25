package com.rssolplan.edu.domain.user;

import com.rssolplan.edu.domain.school.SchoolUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @Query("""
        select su from SchoolUser su
        join fetch su.school s
        join su.user u
        where u.id = :userId and s.id = u.activeSchoolId and su.employmentStatus = :status
    """)
    Optional<SchoolUser> findActiveMembership(@Param("userId") Long userId,
                                               @Param("status") SchoolUser.EmploymentStatus status);

    Optional<User> findByProviderAndProviderId(String provider, String providerId);

    Optional<User> findByProviderAndProviderIdIgnoreCase(String provider, String providerId);

    //로컬개발용
    Optional<User> findByEmail(String email);
}
