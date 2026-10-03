package com.rssolplan.edu.domain.voicecommand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.voicecommand.dto.DraftData;
import com.rssolplan.edu.global.exception.DraftExpiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CommandDraftStore {

    private static final String KEY_PREFIX = "ai:draft:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Stores the draft for ten minutes and returns its opaque Redis token.
     *
     * @throws IllegalStateException if serialization or Redis storage fails
     */
    public String save(DraftData data) {
        String token = KEY_PREFIX + UUID.randomUUID();
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(token, json, TTL);
            return token;
        } catch (Exception e) {
            throw new IllegalStateException("드래프트 저장 중 오류가 발생했습니다.", e);
        }
    }

    /**
     * Atomically retrieves and deletes a draft to prevent duplicate submission.
     * The token remains consumed if decoding or later processing fails. Redis access failures propagate.
     *
     * @throws DraftExpiredException if the token is missing or expired
     * @throws IllegalStateException if the stored draft cannot be decoded
     */
    public DraftData getAndDelete(String token) {
        // GETDEL — Spring Data Redis 2.6+ 지원 (Spring Boot 3.x)
        String json = redisTemplate.opsForValue().getAndDelete(token);
        if (json == null) {
            throw new DraftExpiredException("드래프트 토큰이 만료되었거나 존재하지 않습니다.");
        }
        try {
            return objectMapper.readValue(json, DraftData.class);
        } catch (Exception e) {
            throw new IllegalStateException("드래프트 데이터 파싱 중 오류가 발생했습니다.", e);
        }
    }
}
