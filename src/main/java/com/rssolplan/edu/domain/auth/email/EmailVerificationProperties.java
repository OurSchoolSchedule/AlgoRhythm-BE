package com.rssolplan.edu.domain.auth.email;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "email.verification")
public class EmailVerificationProperties {

    @Min(1)
    private int expiration = 5;

    @NotEmpty
    private List<@NotBlank String> allowedDomains = List.of("ewha.ac.kr");
}
