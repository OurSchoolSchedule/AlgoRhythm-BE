package com.rssolplan.edu.domain.auth;

import com.rssolplan.edu.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {
    Optional<UserRefreshToken> findByRefreshToken(String refreshToken);
    void deleteByUser(User user);

    @Modifying
    @Query("delete from UserRefreshToken t where t.user.id = :userId")
    void deleteForVerifiedLogin(@Param("userId") Long userId);
}
