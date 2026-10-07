package com.rssolplan.edu.domain.auth.email;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "email.resend")
public class ResendEmailProperties {

    @NotBlank
    private String apiKey;

    @NotBlank
    private String from;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(30);
}
