package com.rssolplan.edu.domain.auth.passkey;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.rssolplan.edu.domain.auth.AuthService;
import com.rssolplan.edu.domain.auth.provider.KakaoProvider;
import com.rssolplan.edu.domain.auth.provider.SocialProfile;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import com.rssolplan.edu.global.security.email_verification.EmailService;
import com.yubico.webauthn.data.ByteArray;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@SpringBootTest(properties = "app.passkeys.environment=preview")
@PasskeyTestSchema
@EnabledIfEnvironmentVariable(named = "BE_A2_DB_TEST", matches = "1")
class PasskeyDatabaseTest {
    @Autowired PasskeyService passkeys;
    @Autowired PasskeyStore store;
    @Autowired PasskeyRealm realm;
    @Autowired PasskeyFlows flows;
    @Autowired EmailService email;
    @Autowired JdbcTemplate db;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository users;
    @Autowired Environment env;
    @Autowired TransactionTemplate tx;
    @Autowired JwtTokenProvider jwt;
    @Autowired AuthService auth;
    @MockitoBean KakaoProvider kakao;
    User user;
    final ByteArray id = new ByteArray(PasskeyStore.randomBytes());

    @BeforeEach
    void existingEmailAccount() {
        String address = "a2-" + UUID.randomUUID() + "@ewha.ac.kr";
        user =
                users.save(
                        User.builder()
                                .provider("email")
                                .providerId(address)
                                .email("contact@example.invalid")
                                .username("기존 교사")
                                .build());
    }

    void enrollFixture() {
        tx.executeWithoutResult(
                s -> {
                    store.lock(user.getId());
                    store.insert(user.getId(), id, new ByteArray(new byte[] {1}), 0, false, false);
                    store.advance(user.getId());
                });
    }

    @Test
    void existingVerifiedIdentityKeepsIdNameAndContactThroughTransition() {
        var first = email.issueTokensForVerifiedEmail(user.getProviderId(), null, null, null);
        assertThat(first.getUserId()).isEqualTo(user.getId());
        assertThat(first.isNewUser()).isFalse();
        assertThat(first.getNextStep()).isEqualTo("REGISTER");
        enrollFixture();
        assertThatThrownBy(() -> passkeys.initialRegistration(first.getFlowToken()))
                .isInstanceOf(PasskeyProblem.class);
        assertThat(
                        email.issueTokensForVerifiedEmail(user.getProviderId(), null, null, null)
                                .getNextStep())
                .isEqualTo("AUTHENTICATE");
        var stored = users.findById(user.getId()).orElseThrow();
        assertThat(stored.getUsername()).isEqualTo("기존 교사");
        assertThat(stored.getEmail()).isEqualTo("contact@example.invalid");
    }

    @Test
    void credentialLabelsUseVerifiedAddressesAndKeepTheOpaqueHandle() {
        String verified = "bound-" + UUID.randomUUID() + "@ewha.ac.kr";
        db.update(
                "INSERT INTO roster_verified_account(user_id,auth_email) VALUES (?,?)",
                user.getId(),
                verified);
        var step = passkeys.afterVerifiedEmail(user.getId());
        var identity = passkeys.initialRegistration(step.flowToken()).publicKey().path("user");
        assertThat(identity.path("name").asText()).isEqualTo(verified);
        assertThat(identity.path("displayName").asText()).isEqualTo(verified);
        var handle = store.getUserHandleForUsername(verified).orElseThrow();
        assertThat(handle.getBase64Url()).isEqualTo(identity.path("id").asText());
        assertThat(store.getUsernameForUserHandle(handle)).contains(verified);
        assertThat(store.getUserHandleForUsername(user.getId().toString())).contains(handle);
        enrollFixture();
        assertThat(store.getCredentialIdsForUsername(verified)).hasSize(1);
        assertThat(store.getCredentialIdsForUsername(user.getEmail())).isEmpty();
    }

    @Test
    void onlyOneConcurrentStartCanConsumeAnEmailGrant() throws Exception {
        enrollFixture();
        var grant = passkeys.afterVerifiedEmail(user.getId()).flowToken();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Callable<Boolean> attempt =
                () -> {
                    start.await();
                    try {
                        passkeys.initialRegistration(grant);
                        return true;
                    } catch (PasskeyProblem e) {
                        return false;
                    }
                };
        try {
            var a = pool.submit(attempt);
            var b = pool.submit(attempt);
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void existingAccountCanEnrollFromEmailWithoutRecoveryCodeOrChangingIdentity() {
        enrollFixture();
        var handle = store.getUserHandleForUsername(user.getProviderId()).orElseThrow();
        var emailStep = email.issueTokensForVerifiedEmail(user.getProviderId(), null, "UNKNOWN", "다른 이름");
        assertThat(emailStep.getNextStep()).isEqualTo("AUTHENTICATE");
        assertThat(emailStep.getUserId()).isEqualTo(user.getId());
        assertThat(emailStep.getSchoolMembership()).isNull();
        var options = passkeys.initialRegistration(emailStep.getFlowToken()).publicKey();
        assertThat(options.path("user").path("id").asText()).isEqualTo(handle.getBase64Url());
        assertThat(options.path("user").path("name").asText()).isEqualTo(user.getProviderId());
        assertThat(options.path("excludeCredentials").get(0).path("id").asText())
                .isEqualTo(id.getBase64Url());
        assertThat(passkeys.devices(user.getId())).extracting(PasskeyStore.Device::id)
                .containsExactly(id.getBase64Url());
        assertThat(users.findById(user.getId()).orElseThrow().getUsername()).isEqualTo("기존 교사");
        assertThatThrownBy(() -> passkeys.initialRegistration(emailStep.getFlowToken()))
                .isInstanceOf(PasskeyProblem.class);
    }

    @Test
    void expiredEmailGrantCannotEnrollAnotherKey() {
        enrollFixture();
        var grant = passkeys.afterVerifiedEmail(user.getId()).flowToken();
        String key = "passkey:" + realm.id() + ":email:" + grant;
        assertThat(redis.getExpire(key)).isBetween(295L, 300L);
        redis.expire(key, Duration.ofMillis(5));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertThatThrownBy(() -> passkeys.initialRegistration(grant))
                .isInstanceOf(PasskeyProblem.class);
        assertThat(passkeys.devices(user.getId())).hasSize(1);
    }

    @Test
    void legacyRecoveryEmailGrantBecomesAdditiveEnrollment() {
        enrollFixture();
        var account = tx.execute(s -> store.lock(user.getId()));
        String grant = flows.put("email", new PasskeyFlows.State(
                PasskeyFlows.Action.RECOVER, user.getId(), account.revision(), null, null));
        var options = passkeys.initialRegistration(grant);
        var ceremony = flows.take("register", options.ceremonyId());
        assertThat(ceremony.action()).isEqualTo(PasskeyFlows.Action.EMAIL_REGISTER);
        assertThat(ceremony.user()).isEqualTo(user.getId());
        assertThat(ceremony.target()).isNull();
        assertThat(passkeys.devices(user.getId())).hasSize(1);
    }

    @Test
    void loginChallengeCannotBeUsedAsAnEmailEnrollmentGrant() {
        enrollFixture();
        var login = passkeys.login();
        assertThatThrownBy(() -> passkeys.initialRegistration(login.ceremonyId()))
                .isInstanceOf(PasskeyProblem.class);
        assertThat(passkeys.devices(user.getId())).hasSize(1);
    }

    @Test
    void expiresPendingCeremonyInRedis() {
        var options = passkeys.login();
        String key = "passkey:" + realm.id() + ":assert:" + options.ceremonyId();
        assertThat(redis.getExpire(key)).isBetween(295L, 300L);
        redis.expire(key, Duration.ofMillis(5));
        await().atMost(Duration.ofSeconds(2)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
        assertThatThrownBy(() -> passkeys.finishAssertion(options.ceremonyId(), null, null))
                .isInstanceOf(PasskeyProblem.class)
                .hasMessageContaining("다시");
    }

    @Test
    void expiredRecoveryCannotAuthorizeAReplacement() throws Exception {
        enrollFixture();
        byte[] code = PasskeyStore.randomBytes();
        store.saveRecovery(user.getId(), MessageDigest.getInstance("SHA-256").digest(code));
        db.update(
                "UPDATE passkey_account SET recovery_expires_at=UTC_TIMESTAMP()-INTERVAL 1 DAY"
                        + " WHERE rp_id=? AND user_id=?",
                realm.id(),
                user.getId());
        var emailStep = passkeys.afterVerifiedEmail(user.getId());
        assertThatThrownBy(
                        () ->
                                passkeys.recover(
                                        emailStep.flowToken(), new ByteArray(code).getBase64Url()))
                .isInstanceOf(PasskeyProblem.class);
        assertThat(passkeys.devices(user.getId())).hasSize(1);
    }

    @Test
    void rpNamespacesAndTokenIssuersAreIndependentEvenWithSameTestInfrastructure()
            throws Exception {
        enrollFixture();
        var prodRealm = new PasskeyRealm("production");
        var prodStore = new PasskeyStore(db, prodRealm);
        assertThat(prodRealm.origin()).isEqualTo("https://ourschoolschedule.vercel.app");
        assertThat(prodRealm.id()).isEqualTo("ourschoolschedule.vercel.app");
        assertThat(prodStore.lookupAll(id)).isEmpty();
        var prodJwt =
                new JwtTokenProvider(env.getRequiredProperty("jwt.secret"), db, prodRealm, env);
        String fp =
                HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes()));
        String token = jwt.issue(user.getId(), "access", "passkey", fp);
        assertThat(jwt.validate(token)).isTrue();
        assertThat(prodJwt.validate(token)).isFalse();
        assertThatThrownBy(() -> new PasskeyRealm("unconfigured"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void bothLinkedLoginEntryPointsRequirePasskeyAndOldSocialSessionStopsWorking(
            boolean enrollSocialUser) {
        String socialId = "test-" + UUID.randomUUID();
        var social =
                users.save(
                        User.builder()
                                .provider("kakao")
                                .providerId(socialId)
                                .username("연동 교사")
                                .build());
        when(kakao.fetchProfile("provider-token"))
                .thenReturn(
                        SocialProfile.builder()
                                .providerId(socialId)
                                .username("연동 교사")
                                .email(user.getProviderId())
                                .build());
        when(kakao.getAccessTokenFromCode("callback-code")).thenReturn("provider-token");
        var before = auth.handleKakaoAppLogin("provider-token");
        assertThat(jwt.validate(before.getAccessToken())).isTrue();
        String renewed = auth.refresh(before.getRefreshToken()).getAccessToken();
        if (enrollSocialUser) user = social;
        enrollFixture();
        assertThat(jwt.validate(renewed)).isFalse();
        assertThat(jwt.validate(before.getAccessToken())).isFalse();
        assertThatThrownBy(() -> auth.refresh(before.getRefreshToken()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> auth.handleKakaoCallback("callback-code"))
                .isInstanceOf(PasskeyProblem.class);
        assertThatThrownBy(() -> auth.handleKakaoAppLogin("provider-token"))
                .isInstanceOf(PasskeyProblem.class);
    }
}
