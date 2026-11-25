package com.rssolplan.edu.global.security.email_verification;

import com.rssolplan.edu.domain.auth.email.EmailVerificationProperties;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailVerificationFailureTest {
    @Test
    void acceptedCodeFollowedByDatabaseFailureReturnsRetryGuidance() throws Exception {
        EmailService email = mock(EmailService.class);
        String address = "teacher@example.invalid";
        when(email.verifyCode(address, "123456")).thenReturn(true);
        when(email.issueTokensForVerifiedEmail(address, null, null, null))
                .thenThrow(new BadSqlGrammarException("lookup", "SELECT * FROM roster_verified_account",
                        new SQLException("table missing", "42S02", 1146)));

        var controller = new EmailVerificationController(email, new EmailVerificationProperties());
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/auth/email-verification/verify")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"teacher@example.invalid\",\"code\":\"123456\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("EMAIL_VERIFICATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(
                        "지금은 이메일 인증을 완료할 수 없습니다. 잠시 후 새 인증 코드를 받아 다시 시도해 주세요."))
                .andExpect(jsonPath("$.flowToken").doesNotExist());
    }
}
