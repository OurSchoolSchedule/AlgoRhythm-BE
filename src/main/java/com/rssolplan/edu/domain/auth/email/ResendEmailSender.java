package com.rssolplan.edu.domain.auth.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Slf4j
public class ResendEmailSender {

    private final RestClient restClient;
    private final ResendEmailProperties properties;

    public ResendEmailSender(RestClient restClient, ResendEmailProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public String send(String recipient, String subject, String html) {
        try {
            SendEmailResponse response = restClient.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SendEmailRequest(properties.getFrom(), List.of(recipient), subject, html))
                    .retrieve()
                    .body(SendEmailResponse.class);

            if (response == null || response.id() == null || response.id().isBlank()) {
                log.error("Resend 응답에 이메일 ID가 없습니다.");
                throw new EmailDeliveryException();
            }
            return response.id();
        } catch (RestClientResponseException e) {
            // 응답 본문에는 수신 주소 등이 포함될 수 있으므로 상태 코드만 기록합니다.
            log.error("Resend 이메일 발송 요청 실패: HTTP {}", e.getStatusCode().value());
            throw new EmailDeliveryException();
        } catch (RestClientException e) {
            log.error("Resend 이메일 발송 통신 실패: {}", e.getClass().getSimpleName());
            throw new EmailDeliveryException();
        }
    }

    private record SendEmailRequest(String from, List<String> to, String subject, String html) {}

    private record SendEmailResponse(String id) {}
}
