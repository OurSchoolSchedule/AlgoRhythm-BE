package com.rssolplan.edu.global.security.email_verification;

import com.rssolplan.edu.domain.auth.EmailVerificationHistory;
import com.rssolplan.edu.domain.auth.EmailVerificationHistoryRepository;
import com.rssolplan.edu.domain.auth.UserRefreshToken;
import com.rssolplan.edu.domain.auth.UserRefreshTokenRepository;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import com.rssolplan.edu.global.security.email_verification.dto.EmailVerificationToken;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final StringRedisTemplate redisTemplate; // Redis 활용
    private final SpringTemplateEngine templateEngine; // Thymeleaf 활용
    private final EmailVerificationHistoryRepository historyRepository; // DB 기록
    private final UserRepository users;
    private final UserRefreshTokenRepository refreshRepo;
    private final JwtTokenProvider jwt;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${email.verification.allowed-domains:ewha.ac.kr}")
    private String allowedDomainsConfig;

    private static final String AUTH_PREFIX = "EMAIL_AUTH:";

    @PostConstruct
    public void testDns() {
        try {
            InetAddress addr = InetAddress.getByName("smtp.gmail.com");
            log.info("SMTP IP = {}", addr.getHostAddress());
        } catch (Exception e) {
            log.error("DNS 조회 실패", e);
        }
    }

    

    public void sendVerificationEmail(String email) {
        // 1. 도메인 체크 (설정 가능한 도메인)
        if (!isValidEmailDomain(email)) {
            throw new IllegalArgumentException("허용된 이메일 도메인만 인증이 가능합니다.");
        }

        // 2. 6자리 숫자 코드 생성
        String code = String.valueOf((int)(Math.random() * 899999) + 100000);

        // 3. Redis에 저장 (5분간 유효, TTL 자동 관리)
        //redisTemplate.opsForValue().set(AUTH_PREFIX + email, code, Duration.ofMinutes(5));
        try {
    redisTemplate.opsForValue().set(
        AUTH_PREFIX + email,
        code,
        Duration.ofMinutes(5)
    );
} catch (Exception e) {
    log.error("REDIS 저장 실패", e);
    throw e;
}

        // 4. DB에 발송 기록 저장
        EmailVerificationHistory history = EmailVerificationHistory.builder()
                .email(email)
                .code(code)
                .status(EmailVerificationHistory.VerificationStatus.SENT)
                .createdAt(LocalDateTime.now())
                .build();
        historyRepository.save(history);
        log.info("[EmailService] DB에 발송 기록 저장: {} (ID: {})", email, history.getId());

        // 5. HTML 메일 발송
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(email);
            helper.setSubject("[우리학교시간표] 교사 인증 번호 안내");

            // Thymeleaf 템플릿에 데이터 주입
            Context context = new Context();
            context.setVariable("code", code);
            String html = templateEngine.process("email-auth", context);

            helper.setText(html, true);
            mailSender.send(message);

            log.info("[EmailService] 인증 코드 이메일 발송 완료: {}", email);

        } catch (MessagingException e) {
            log.error("메일 발송 실패: {}", e); //.getMessage()
            throw new RuntimeException("이메일 발송 중 오류가 발생했습니다.");
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
        List<String> allowedDomains = Arrays.asList(allowedDomainsConfig.split(","));
        return allowedDomains.stream()
                .map(String::trim)
                .anyMatch(domain -> email.endsWith("@" + domain));
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
