package com.rssolplan.edu.domain.auth.passkey;

import com.rssolplan.edu.domain.demoreset.DemoRound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;

@Component
@RequiredArgsConstructor
class PasskeyFlows {
    enum Action {
        FIRST,
        RECOVER,
        LOGIN,
        ADD,
        DELETE,
        ROTATE,
        DEMO_RESET
    }

    record State(Action action, Long user, long revision, String target, String options) {
        State withOptions(String json) {
            return new State(action, user, revision, target, json);
        }
    }

    static final Duration TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final PasskeyRealm realm;
    private final DemoRound round;

    String put(String stage, State state) {
        String token =
                Base64.getUrlEncoder().withoutPadding().encodeToString(PasskeyStore.randomBytes());
        try {
            redis.opsForValue().set(key(stage, token), json.writeValueAsString(state), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode passkey state", e);
        }
        return token;
    }

    State take(String stage, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw PasskeyProblem.stale();
        String value = redis.opsForValue().getAndDelete(key(stage, token));
        if (value == null) throw PasskeyProblem.stale();
        try {
            return json.readValue(value, State.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read passkey state", e);
        }
    }

    private String key(String stage, String token) {
        return round.prefix() + "passkey:" + realm.id() + ":" + stage + ":" + token;
    }
}
