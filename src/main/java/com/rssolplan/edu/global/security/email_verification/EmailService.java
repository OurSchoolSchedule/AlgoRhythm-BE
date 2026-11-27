package com.rssolplan.edu.global.security.email_verification;

import com.rssolplan.edu.domain.demoreset.DemoRound;
import com.rssolplan.edu.domain.auth.EmailVerificationHistory;
import com.rssolplan.edu.domain.auth.EmailVerificationHistoryRepository;
import com.rssolplan.edu.domain.auth.passkey.PasskeyService;
import com.rssolplan.edu.domain.auth.passkey.PasskeyStore;
import com.rssolplan.edu.domain.auth.email.EmailDeliveryException;
import com.rssolplan.edu.domain.auth.email.EmailVerificationProperties;
import com.rssolplan.edu.domain.auth.email.ResendEmailSender;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.roster.VerifiedRosterSignup;
import com.rssolplan.edu.domain.auth.email.LoginEmail;
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
    private final VerifiedRosterSignup rosterSignup;
    private final PasskeyService passkeys;
    private final PasskeyStore passkeyStore;
    private final DemoRound round;

    private static final String AUTH_PREFIX = "EMAIL_AUTH:";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final SecureRandom CODE_RANDOM = new SecureRandom();
    private static final RedisScript<Long> DELETE_MATCHING_CODE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final RedisScript<Long> VERIFY_LIMITED_CODE = new DefaultRedisScript<>(
            "local failures = tonumber(redis.call('get', KEYS[2]) or '0'); "
                    + "if failures >= tonumber(ARGV[2]) then return 0 end; "
                    + "local code = redis.call('get', KEYS[1]); "
                    + "if not code then return 0 end; "
                    + "if code == ARGV[1] then redis.call('del', KEYS[1], KEYS[2]); return 1 end; "
                    + "failures = redis.call('incr', KEYS[2]); "
                    + "if failures == 1 then redis.call('pexpire', KEYS[2], ARGV[3]) end; "
                    + "if failures >= tonumber(ARGV[2]) then redis.call('del', KEYS[1]) end; "
                    + "return 0", Long.class);

    public void sendVerificationEmail(String email) {
        email = LoginEmail.require(email);

        String code = String.valueOf(CODE_RANDOM.nextInt(900000) + 100000);
        Context context = new Context();
        context.setVariable("code", code);
        context.setVariable("expirationMinutes", verificationProperties.getExpiration());
        String html = templateEngine.process("email-auth", context);

        String key = round.prefix() + AUTH_PREFIX + email;
        redisTemplate.opsForValue().set(key, code,
                Duration.ofMinutes(verificationProperties.getExpiration()));

        String messageId;
        try {
            messageId = mailSender.send(email, "[우리학교시간표] 교사 인증 번호 안내", html);
        } catch (EmailDeliveryException e) {
            recordDeliveryFailure(email, key, code, e);
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

    private void recordDeliveryFailure(String email, String key, String code, EmailDeliveryException failure) {
        try {
            // 동시에 새 발송 요청이 들어온 경우 새 인증 코드를 삭제하지 않습니다.
            redisTemplate.execute(DELETE_MATCHING_CODE, List.of(key), code);
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
        email = LoginEmail.require(email);
        round.lockForVerification();
        String key = round.prefix() + AUTH_PREFIX + email;
        // The failed-attempt budget survives resends so requesting another code cannot reset it.
        // Redis checks, counts and consumes atomically, including concurrent verification requests.
        Long consumed = redisTemplate.execute(VERIFY_LIMITED_CODE, List.of(key, key + ":attempts"),
                code, String.valueOf(MAX_FAILED_ATTEMPTS),
                String.valueOf(Duration.ofMinutes(verificationProperties.getExpiration()).toMillis()));
        if (Long.valueOf(1L).equals(consumed)) {
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

    @Transactional
    public EmailVerificationToken issueTokensForVerifiedEmail(String email, Long authenticatedUserId,
                                                               String schoolCode, String displayName) {
        // A repeat email proof may enroll a new device; it cannot change school membership.
        email = LoginEmail.require(email);
        Long existing = passkeyStore.verifiedUserId(email);
        if (authenticatedUserId == null && existing != null && passkeys.requiresPasskey(existing)) {
            var step = passkeys.afterVerifiedEmail(existing);
            return EmailVerificationToken.builder().userId(existing).isNewUser(false)
                    .nextStep(step.nextStep()).flowToken(step.flowToken()).build();
        }
        var signup = rosterSignup.complete(email, authenticatedUserId);
        User user = signup.user();

        var step = passkeys.afterVerifiedEmail(user.getId());
        return EmailVerificationToken.builder()
                .nextStep(step.nextStep())
                .flowToken(step.flowToken())
                .userId(user.getId())
                .isNewUser(signup.newUser())
                .schoolMembership(signup.schoolMembership())
                .build();
    }

}
