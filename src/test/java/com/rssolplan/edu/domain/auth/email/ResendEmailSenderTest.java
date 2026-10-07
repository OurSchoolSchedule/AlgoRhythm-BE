package com.rssolplan.edu.domain.auth.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ResendEmailSenderTest {

    private MockRestServiceServer server;
    private ResendEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer re_test_key");
        server = MockRestServiceServer.bindTo(builder).build();
        ResendEmailProperties properties = new ResendEmailProperties();
        properties.setFrom("우리학교시간표 <no-reply@school.example>");
        sender = new ResendEmailSender(builder.build(), properties);
    }

    @Test
    void sendsHtmlWithVerifiedDomainSenderAndBearerToken() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_test_key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "from": "우리학교시간표 <no-reply@school.example>",
                          "to": ["teacher@ewha.ac.kr"],
                          "subject": "교사 인증 번호 안내",
                          "html": "<strong>123456</strong>"
                        }
                        """))
                .andRespond(withSuccess("{\"id\":\"email-123\"}", MediaType.APPLICATION_JSON));

        assertEquals("email-123", sender.send("teacher@ewha.ac.kr", "교사 인증 번호 안내",
                "<strong>123456</strong>"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500})
    void providerErrorsDoNotExposeResponseBodyOrCredentials(int status) {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"private-provider-detail re_test_key\"}"));

        EmailDeliveryException failure = assertThrows(EmailDeliveryException.class,
                () -> sender.send("teacher@ewha.ac.kr", "인증", "<p>123456</p>"));

        assertFalse(failure.getMessage().contains("private-provider-detail"));
        assertFalse(failure.getMessage().contains("re_test_key"));
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void timeoutsAreReportedAsDeliveryFailures() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withException(new SocketTimeoutException("private transport detail")));

        assertThrows(EmailDeliveryException.class,
                () -> sender.send("teacher@ewha.ac.kr", "인증", "<p>123456</p>"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"id\":\"\"}", "{\"id\":null}", "", "invalid-json"})
    void missingMessageIdOrInvalidJsonIsNotReportedAsSuccess(String body) {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThrows(EmailDeliveryException.class,
                () -> sender.send("teacher@ewha.ac.kr", "인증", "<p>123456</p>"));
        server.verify();
    }
}
