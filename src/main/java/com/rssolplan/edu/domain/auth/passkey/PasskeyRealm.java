package com.rssolplan.edu.domain.auth.passkey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class PasskeyRealm {
    private final URI origin;

    public PasskeyRealm(@Value("${app.passkeys.environment:production}") String environment) {
        origin =
                URI.create(
                        switch (environment) {
                            case "production" -> "https://algo-rhythm-sihyes-projects.vercel.app";
                            case "preview" ->
                                    "https://algo-rhythm-git-fe-develop-sihyes-projects.vercel.app";
                            case "local" -> "http://localhost:5173";
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown passkey environment");
                        });
    }

    public String id() {
        return origin.getHost();
    }

    public String origin() {
        return origin.toString();
    }
}
