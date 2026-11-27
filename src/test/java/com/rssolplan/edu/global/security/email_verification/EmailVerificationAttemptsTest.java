package com.rssolplan.edu.global.security.email_verification;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/** Exercises the real Redis script, including races and resends, with isolated test addresses. */
@SpringBootTest(properties = "app.passkeys.environment=preview")
@EnabledIfEnvironmentVariable(named = "BE_A2_DB_TEST", matches = "1")
class EmailVerificationAttemptsTest {
    @Autowired EmailService email;
    @Autowired StringRedisTemplate redis;
    String address;
    String key;

    @BeforeEach
    void isolatedAddress() {
        address = "attempts-" + UUID.randomUUID() + "@ewha.ac.kr";
        key = "EMAIL_AUTH:" + address;
        issue("123456");
    }

    void issue(String code) {
        redis.opsForValue().set(key, code, Duration.ofMinutes(5));
    }

    @Test
    void validCodeBeforeLimitConsumesCodeAndClearsFailures() {
        for (int i = 0; i < 4; i++) assertThat(email.verifyCode(address, "000000")).isFalse();
        assertThat(email.verifyCode(address, "123456")).isTrue();
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.hasKey(key + ":attempts")).isFalse();
        assertThat(email.verifyCode(address, "123456")).isFalse();
        issue("654321");
        assertThat(email.verifyCode(address, "654321")).isTrue();
    }

    @Test
    void fiveFailuresInvalidateCodeAndResendCannotResetTheBudget() {
        for (int i = 0; i < 5; i++) assertThat(email.verifyCode(address, "000000")).isFalse();
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.opsForValue().get(key + ":attempts")).isEqualTo("5");
        assertThat(redis.getExpire(key + ":attempts")).isBetween(295L, 300L);
        issue("654321");
        assertThat(email.verifyCode(address, "654321")).isFalse();
        redis.expire(key + ":attempts", Duration.ofMillis(5));
        await().atMost(Duration.ofSeconds(2))
                .until(() -> !Boolean.TRUE.equals(redis.hasKey(key + ":attempts")));
        assertThat(email.verifyCode(address, "654321")).isTrue();
    }

    @Test
    void concurrentWrongGuessesCannotExceedTheLimit() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    return email.verifyCode(address, "000000");
                }));
            }
            start.countDown();
            for (var attempt : attempts) assertThat(attempt.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(redis.opsForValue().get(key + ":attempts")).isEqualTo("5");
            assertThat(redis.hasKey(key)).isFalse();
            issue("654321");
            assertThat(email.verifyCode(address, "654321")).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredCodeCannotAuthenticateOrCreateALockoutBudget() {
        redis.delete(key);
        assertThat(email.verifyCode(address, "123456")).isFalse();
        assertThat(redis.hasKey(key + ":attempts")).isFalse();
    }
}
