package com.rssolplan.edu.domain.auth.email;

import com.rssolplan.edu.global.config.RestClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ResendEmailConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
            .withUserConfiguration(ResendEmailConfiguration.class, RestClientConfig.class)
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

    @Test
    void bindsEnvironmentVariablesAndRecipientDomainListFromApplicationYaml() {
        runner.withPropertyValues("RESEND_API_KEY=re_test_key",
                        "RESEND_FROM_EMAIL=우리학교시간표 <no-reply@school.example>")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ResendEmailSender.class);
                    ResendEmailProperties resend = context.getBean(ResendEmailProperties.class);
                    assertThat(resend.getApiKey()).isEqualTo("re_test_key");
                    assertThat(resend.getFrom()).isEqualTo("우리학교시간표 <no-reply@school.example>");
                    EmailVerificationProperties verification = context.getBean(EmailVerificationProperties.class);
                    assertThat(verification.getAllowedDomains()).containsExactly("ewha.ac.kr", "gmail.com");
                    assertThat(verification.getExpiration()).isEqualTo(5);
                    // Resend's private client must not make the existing shared RestClient ambiguous.
                    assertThat(context).hasSingleBean(RestClient.class);
                });
    }

    @Test
    void refusesToStartWithAnEmptyApiKey() {
        runner.withPropertyValues("RESEND_API_KEY=", "RESEND_FROM_EMAIL=no-reply@school.example")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithAnEmptySenderAddress() {
        runner.withPropertyValues("RESEND_API_KEY=re_test_key", "RESEND_FROM_EMAIL=")
                .run(context -> assertThat(context).hasFailed());
    }
}
