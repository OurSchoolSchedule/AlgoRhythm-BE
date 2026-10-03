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

    /** DraftData를 저장하고 draftToken(key)을 반환한다. */
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
     * 토큰으로 DraftData를 조회하고 동시에 Redis에서 삭제한다.
     * 중복 제출(double-submit) 방지를 위해 getAndDelete를 원자적으로 수행한다.
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
