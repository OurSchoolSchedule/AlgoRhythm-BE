package com.rssolplan.edu.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * .env 파일 로드 설정
 * spring-dotenv 라이브러리를 통해 .env 파일 자동 로드
 *
 * [주의사항]
 * 1. .env 파일은 프로젝트 루트 경로에 위치해야 함
 * 2. 환경변수가 없으면 application.yml의 기본값(default value)이 사용됨
 * 3. null 값은 HikariCP에서 "NullString" 에러를 발생시킬 수 있음
 */
@Configuration
@Slf4j
public class EnvConfig {

    @Autowired
    public void loadEnv(Environment env) {
        log.info("========== 환경 변수 설정 (초기화) ==========");

        // 데이터베이스 설정
        log.info("[Database]");
        log.info("  - DATASOURCE_URL: {}", env.getProperty("spring.datasource.url"));
        log.info("  - DATASOURCE_USERNAME: {}", env.getProperty("spring.datasource.username"));
        log.info("  - DATASOURCE_DRIVER: {}", env.getProperty("spring.datasource.driver-class-name"));

        // Redis 설정
        log.info("[Redis]");
        log.info("  - REDIS_HOST: {}", env.getProperty("spring.data.redis.host"));
        log.info("  - REDIS_PORT: {}", env.getProperty("spring.data.redis.port"));

        // OAuth 설정
        log.info("[OAuth - Kakao]");
        log.info("  - CLIENT_ID: {}", maskString(env.getProperty("oauth.kakao.client-id")));
        log.info("  - REDIRECT_URI: {}", env.getProperty("oauth.kakao.redirect-uri"));

        // JWT 설정
        log.info("[JWT]");
        log.info("  - SECRET configured: {}", env.getProperty("jwt.secret") != null);

        // 시스템 환경변수 확인 (디버깅용)
        log.debug("[System Environment Variables]");
        log.debug("  - SPRING_DATASOURCE_URL: {}", System.getenv("SPRING_DATASOURCE_URL"));
        log.debug("  - REDIS_HOST: {}", System.getenv("REDIS_HOST"));

        log.info("========================================");
    }

    /**
     * 민감한 정보를 마스킹하는 유틸리티 메서드
     */
    private String maskString(String value) {
        if (value == null || value.isEmpty()) {
            return "[NOT SET]";
        }
        if (value.length() <= 4) {
            return "*".repeat(value.length());
        }
        return value.substring(0, 2) + "*".repeat(value.length() - 4) + value.substring(value.length() - 2);
    }
}

