package com.rssolplan.edu.domain.auth.email;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "email.verification")
public class EmailVerificationProperties {

    @Min(1)
    private int expiration = 5;
}
