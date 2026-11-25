package com.rssolplan.edu.domain.neis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Component
@RequiredArgsConstructor
class SchoolSearchCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
            local count = redis.call('INCR',KEYS[1])
            if count == 1 then redis.call('EXPIRE',KEYS[1],60) end
            return count
            """, Long.class);

    void count(long userId) {
        try {
            Long n = redis.execute(COUNT, List.of("neis:v1:search-limit:" + userId));
            if (n == null) throw new NeisClient.Unavailable();
            if (n > 30) throw new NeisSchoolException(HttpStatus.TOO_MANY_REQUESTS, "NEIS_RATE_LIMITED",
                    "학교 검색은 분당 30회까지 가능합니다.");
        } catch (DataAccessException ex) {
            throw new NeisClient.Unavailable();
        }
    }

    String key(String q, String office, String kind, int page, int size) {
        try {
            byte[] data = json.writeValueAsString(new Object[]{q, office, kind, page, size}).getBytes(StandardCharsets.UTF_8);
            return "neis:v1:school-search:" + Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        } catch (JsonProcessingException ex) {
            throw new NeisClient.Unavailable();
        }
    }

    SchoolSearch get(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? null : json.readValue(value, SchoolSearch.class);
        } catch (DataAccessException | JsonProcessingException invalid) {
            return null; // Direct lookup remains bounded by the user limit.
        }
    }

    void put(String key, SchoolSearch result) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(result), Duration.ofHours(24));
        } catch (DataAccessException | JsonProcessingException ignored) {
            // A completed provider response can still be served.
        }
    }
}
