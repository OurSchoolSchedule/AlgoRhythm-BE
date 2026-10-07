package com.rssolplan.edu.global.security.email_verification;

import com.rssolplan.edu.domain.auth.EmailVerificationHistory;
import com.rssolplan.edu.domain.auth.EmailVerificationHistoryRepository;
import com.rssolplan.edu.domain.auth.UserRefreshToken;
import com.rssolplan.edu.domain.auth.UserRefreshTokenRepository;
import com.rssolplan.edu.domain.auth.email.EmailDeliveryException;
import com.rssolplan.edu.domain.auth.email.EmailVerificationProperties;
import com.rssolplan.edu.domain.auth.email.ResendEmailSender;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import com.rssolplan.edu.global.security.email_verification.dto.EmailVerificationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final ResendEmailSender mailSender;
    private final EmailVerificationProperties verificationProperties;
    private final StringRedisTemplate redisTemplate; // Redis 활용
    private final SpringTemplateEngine templateEngine; // Thymeleaf 활용
    private final EmailVerificationHistoryRepository historyRepository; // DB 기록
    private final UserRepository users;
    private final UserRefreshTokenRepository refreshRepo;
    private final JwtTokenProvider jwt;

    private static final String AUTH_PREFIX = "EMAIL_AUTH:";
    private static final SecureRandom CODE_RANDOM = new SecureRandom();
    private static final RedisScript<Long> DELETE_MATCHING_CODE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    public void sendVerificationEmail(String email) {
        // 1. 도메인 체크 (설정 가능한 도메인)
        if (!isValidEmailDomain(email)) {
            throw new IllegalArgumentException("허용된 이메일 도메인만 인증이 가능합니다.");
        }

        String code = String.valueOf(CODE_RANDOM.nextInt(900000) + 100000);
        Context context = new Context();
        context.setVariable("code", code);
        context.setVariable("expirationMinutes", verificationProperties.getExpiration());
        String html = templateEngine.process("email-auth", context);

        redisTemplate.opsForValue().set(AUTH_PREFIX + email, code,
                Duration.ofMinutes(verificationProperties.getExpiration()));

        String messageId;
        try {
            messageId = mailSender.send(email, "[우리학교시간표] 교사 인증 번호 안내", html);
        } catch (EmailDeliveryException e) {
            recordDeliveryFailure(email, code, e);
            throw e;
        }

        historyRepository.save(EmailVerificationHistory.builder()
                .email(email)
                .code(code)
                .status(EmailVerificationHistory.VerificationStatus.SENT)
                .createdAt(LocalDateTime.now())
                .build());
        log.info("[EmailService] Resend 인증 메일 발송 요청 완료: {} (메시지 ID: {})", email, messageId);
    }

    private void recordDeliveryFailure(String email, String code, EmailDeliveryException failure) {
        try {
            // 동시에 새 발송 요청이 들어온 경우 새 인증 코드를 삭제하지 않습니다.
            redisTemplate.execute(DELETE_MATCHING_CODE, List.of(AUTH_PREFIX + email), code);
        } catch (RuntimeException e) {
            log.error("[EmailService] 발송 실패 인증 코드 정리 실패: {}", e.getClass().getSimpleName());
        }
        try {
            historyRepository.save(EmailVerificationHistory.builder()
                    .email(email)
                    .code(code)
                    .status(EmailVerificationHistory.VerificationStatus.FAILED)
                    .reason(failure.getMessage())
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (RuntimeException e) {
            log.error("[EmailService] 발송 실패 이력 저장 실패: {}", e.getClass().getSimpleName());
        }
    }

    public boolean verifyCode(String email, String code) {
        String savedCode = redisTemplate.opsForValue().get(AUTH_PREFIX + email);

        if (savedCode != null && savedCode.equals(code)) {
            redisTemplate.delete(AUTH_PREFIX + email); // 인증 성공 후 즉시 삭제

            // DB에 인증 성공 기록 저장
            EmailVerificationHistory successHistory = EmailVerificationHistory.builder()
                    .email(email)
                    .code(code)
                    .status(EmailVerificationHistory.VerificationStatus.VERIFIED)
                    .verifiedAt(LocalDateTime.now())
                    .build();
            historyRepository.save(successHistory);

            log.info("[EmailService] 인증 성공 기록 저장: {}", email);
            return true;
        }

        // DB에 인증 실패 기록 저장
        EmailVerificationHistory failureHistory = EmailVerificationHistory.builder()
                .email(email)
                .code(code)
                .status(EmailVerificationHistory.VerificationStatus.FAILED)
                .reason("코드 불일치 또는 만료")
                .build();
        historyRepository.save(failureHistory);

        log.warn("[EmailService] 인증 실패 기록 저장: {}", email);
        return false;
    }

    /**
     * 이메일 도메인 검증
     * - 설정된 도메인 목록에서 허용된 도메인인지 확인
     */
    private boolean isValidEmailDomain(String email) {
        if (email == null || email.lastIndexOf('@') <= 0) {
            return false;
        }
        String emailDomain = email.substring(email.lastIndexOf('@') + 1);
        return verificationProperties.getAllowedDomains().stream()
                .map(String::trim)
                .anyMatch(domain -> emailDomain.equalsIgnoreCase(domain));
    }

    @Transactional
    public EmailVerificationToken issueTokensForVerifiedEmail(String email) {
        User user = users.findByEmail(email).orElse(null);
        boolean isNewUser = false;

        if (user == null) {
            user = users.save(User.builder()
                    .provider("email")
                    .providerId(email)
                    .username(extractUsernameFromEmail(email))
                    .email(email)
                    .profileImageUrl("")
                    .build());
            isNewUser = true;
            log.info("[EmailService] 신규 이메일 유저 생성: {} (ID: {})", email, user.getId());
        }

        String accessToken = jwt.generateAccess(user.getId());
        String refreshToken = jwt.generateRefresh(user.getId());

        refreshRepo.deleteByUser(user);
        refreshRepo.save(UserRefreshToken.builder()
                .user(user)
                .refreshToken(refreshToken)
                .expiresAt(LocalDateTime.now().plusDays(14))
                .build());

        return EmailVerificationToken.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .isNewUser(isNewUser)
                .build();
    }


    private String extractUsernameFromEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "사용자";
        }
        return email.substring(0, atIndex);
    }
}
