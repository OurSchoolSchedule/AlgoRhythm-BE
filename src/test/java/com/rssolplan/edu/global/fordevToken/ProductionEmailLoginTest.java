package com.rssolplan.edu.global.fordevToken;

import com.fasterxml.jackson.databind.JsonNode;
import com.rssolplan.edu.domain.auth.email.ResendEmailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "app.auth.dev-login-enabled=true"})
@ActiveProfiles("prod")
@com.rssolplan.edu.domain.auth.passkey.PasskeyTestSchema
@EnabledIfEnvironmentVariable(named = "BE_A1_AUTH_TEST", matches = "1")
class ProductionEmailLoginTest {
    @Autowired TestRestTemplate http;
    @MockitoBean ResendEmailSender mail;

    @Test
    void verifiesDeliveredEmailCodeWithRealStoresWhileDevLoginStaysClosed() {
        String email = "a1-" + UUID.randomUUID() + "@ewha.ac.kr";
        var recipient = Map.of("email", email);
        var api = "/api/auth/email-verification";
        when(mail.send(eq(email), anyString(), anyString())).thenReturn("test-message-id");

        var sent = http.postForEntity(api + "/send", recipient, JsonNode.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().path("success").asBoolean()).isTrue();
        var html = ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq(email), anyString(), html.capture());
        var code = Pattern.compile(">([0-9]{6})<").matcher(html.getValue());
        assertThat(code.find()).isTrue();

        var verification = Map.of("email", email, "code", code.group(1));
        var verified = http.postForEntity(api + "/verify", verification, JsonNode.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = verified.getBody();
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("nextStep").asText()).isEqualTo("REGISTER");
        assertThat(body.hasNonNull("accessToken")).isFalse();
        assertThat(body.hasNonNull("refreshToken")).isFalse();
        String grant = body.path("flowToken").asText();
        assertThat(grant).isNotBlank();

        var replay = http.postForEntity(api + "/verify", verification, JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(replay.getBody().path("success").asBoolean()).isFalse();
        assertThat(replay.getBody().hasNonNull("accessToken")).isFalse();

        String devPath = "/api/auth/dev-token";
        assertThat(http.postForEntity(devPath, recipient, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var headers = new HttpHeaders();
        headers.setBearerAuth(grant);
        var schools = http.exchange("/api/mypage/teacher/schools", HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class);
        assertThat(schools.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var signedIn = new HttpEntity<>(recipient, headers);
        assertThat(http.postForEntity(devPath, signedIn, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
