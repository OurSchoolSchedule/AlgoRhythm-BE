package com.rssolplan.edu.domain.roster;

import com.rssolplan.edu.domain.onboarding.dto.OnboardingResponse;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Entry point for the email-authentication provider, called only after its one-time code succeeds. */
@Service
@RequiredArgsConstructor
public class VerifiedRosterSignup {
    private final UserRepository users;
    private final JdbcTemplate db;
    private final RosterMembershipService memberships;

    public record Result(User user, boolean newUser, OnboardingResponse schoolMembership) {}

    @Transactional
    public Result complete(String email, Long authenticatedUserId) {
        String address = TeacherRosterService.normalizeEmail(email);
        Long boundId = db.queryForList("SELECT user_id FROM roster_verified_account WHERE auth_email=?",
                Long.class, address).stream().findFirst().orElse(null);
        // A mutable profile contact (including a social provider's email claim) is never a login key.
        User user = boundId == null ? users.findByProviderAndProviderIdIgnoreCase("email", address).orElse(null)
                : users.findById(boundId).orElseThrow();
        if (authenticatedUserId != null) {
            if (user != null && !authenticatedUserId.equals(user.getId()))
                throw new IllegalStateException("다른 계정의 인증 이메일입니다.");
            user = users.findById(authenticatedUserId).orElseThrow();
            if ("email".equals(user.getProvider()) && !address.equalsIgnoreCase(user.getProviderId()))
                throw new IllegalStateException("기존 계정의 인증 이메일과 다릅니다.");
        }
        boolean newUser = user == null;
        if (newUser) {
            user = users.save(User.builder().provider("email").providerId(address).email(address).username("")
                    .profileImageUrl("").build());
        }
        OnboardingResponse membership = memberships.existingMembership(user.getId(), user.getActiveSchoolId());
        db.update("""
                INSERT INTO roster_verified_account(user_id,auth_email) VALUES (?,?)
                ON DUPLICATE KEY UPDATE user_id=roster_verified_account.user_id
                """, user.getId(), address);
        String recorded = db.queryForList("SELECT auth_email FROM roster_verified_account WHERE user_id=? FOR UPDATE",
                String.class, user.getId()).stream().findFirst().orElse(null);
        if (!Objects.equals(recorded, address)) throw new IllegalStateException("이미 연결된 인증 이메일입니다.");
        return new Result(user, newUser, membership);
    }
}
