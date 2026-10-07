package com.rssolplan.edu.global.security.email_verification;

import com.rssolplan.edu.domain.auth.EmailVerificationHistory;
import com.rssolplan.edu.domain.auth.EmailVerificationHistoryRepository;
import com.rssolplan.edu.domain.auth.UserRefreshTokenRepository;
import com.rssolplan.edu.domain.auth.email.EmailDeliveryException;
import com.rssolplan.edu.domain.auth.email.EmailVerificationProperties;
import com.rssolplan.edu.domain.auth.email.ResendEmailSender;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock private ResendEmailSender sender;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private EmailVerificationHistoryRepository historyRepository;
    @Mock private UserRepository users;
    @Mock private UserRefreshTokenRepository refreshRepository;
    @Mock private JwtTokenProvider jwt;

    private EmailService service;

    @BeforeEach
    void setUp() {
        EmailVerificationProperties properties = new EmailVerificationProperties();
        properties.setAllowedDomains(List.of("ewha.ac.kr", "gmail.com"));
        properties.setExpiration(7);
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);
        service = new EmailService(sender, properties, redis, templateEngine,
                historyRepository, users, refreshRepository, jwt);
    }

    @Test
    void storesTheSameCodeAsTheRenderedEmailAndRecordsAcceptedMail() {
        when(redis.opsForValue()).thenReturn(values);
        when(sender.send(eq("teacher@gmail.com"), anyString(), anyString())).thenReturn("email-123");

        service.sendVerificationEmail("teacher@gmail.com");

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("EMAIL_AUTH:teacher@gmail.com"), code.capture(), eq(Duration.ofMinutes(7)));
        assertTrue(code.getValue().matches("[1-9][0-9]{5}"));
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("teacher@gmail.com"), eq("[우리학교시간표] 교사 인증 번호 안내"), html.capture());
        assertTrue(html.getValue().contains(code.getValue()));
        assertTrue(html.getValue().contains("7분간"));
        assertFalse(html.getValue().contains("th:text"));

        ArgumentCaptor<EmailVerificationHistory> history = ArgumentCaptor.forClass(EmailVerificationHistory.class);
        var order = inOrder(sender, historyRepository);
        order.verify(sender).send(anyString(), anyString(), anyString());
        order.verify(historyRepository).save(history.capture());
        assertEquals(EmailVerificationHistory.VerificationStatus.SENT, history.getValue().getStatus());
        assertEquals(code.getValue(), history.getValue().getCode());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void rejectedMailRemovesOnlyItsOwnCodeAndRecordsFailure() {
        when(redis.opsForValue()).thenReturn(values);
        EmailDeliveryException failure = new EmailDeliveryException();
        when(sender.send(anyString(), anyString(), anyString())).thenThrow(failure);

        assertSame(failure, assertThrows(EmailDeliveryException.class,
                () -> service.sendVerificationEmail("teacher@ewha.ac.kr")));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("EMAIL_AUTH:teacher@ewha.ac.kr"), code.capture(), any(Duration.class));
        verify(redis).execute(any(RedisScript.class), eq(List.of("EMAIL_AUTH:teacher@ewha.ac.kr")),
                eq(code.getValue()));
        verify(redis, never()).delete(anyString());
        ArgumentCaptor<EmailVerificationHistory> history = ArgumentCaptor.forClass(EmailVerificationHistory.class);
        verify(historyRepository).save(history.capture());
        assertEquals(EmailVerificationHistory.VerificationStatus.FAILED, history.getValue().getStatus());
        assertEquals(code.getValue(), history.getValue().getCode());
        assertEquals(failure.getMessage(), history.getValue().getReason());
    }

    @Test
    void disallowedRecipientDoesNotStoreOrSendAnything() {
        assertThrows(IllegalArgumentException.class,
                () -> service.sendVerificationEmail("teacher@unauthorized.example"));
        verifyNoInteractions(sender, redis, historyRepository);
    }

    @Test
    void redisFailurePreventsMailFromBeingSent() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(values).set(anyString(), anyString(), any(Duration.class));

        assertThrows(IllegalStateException.class,
                () -> service.sendVerificationEmail("teacher@ewha.ac.kr"));
        verifyNoInteractions(sender, historyRepository);
    }

    @Test
    void verifyingACorrectCodeStillConsumesItAndRecordsVerification() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("EMAIL_AUTH:teacher@ewha.ac.kr")).thenReturn("123456");

        assertTrue(service.verifyCode("teacher@ewha.ac.kr", "123456"));

        verify(redis).delete("EMAIL_AUTH:teacher@ewha.ac.kr");
        ArgumentCaptor<EmailVerificationHistory> history = ArgumentCaptor.forClass(EmailVerificationHistory.class);
        verify(historyRepository).save(history.capture());
        assertEquals(EmailVerificationHistory.VerificationStatus.VERIFIED, history.getValue().getStatus());
        assertNotNull(history.getValue().getVerifiedAt());
        verifyNoInteractions(sender);
    }
}
