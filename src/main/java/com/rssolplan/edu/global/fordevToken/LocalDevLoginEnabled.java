package com.rssolplan.edu.global.fordevToken;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

public class LocalDevLoginEnabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return enabled(context.getEnvironment());
    }

    public static boolean enabled(Environment env) {
        return List.of(env.getActiveProfiles()).equals(List.of("local"))
                && "true".equalsIgnoreCase(env.getProperty("app.auth.dev-login-enabled"));
    }
}
