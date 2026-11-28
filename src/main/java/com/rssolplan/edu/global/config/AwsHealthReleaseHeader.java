package com.rssolplan.edu.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Profile("aws")
public class AwsHealthReleaseHeader extends OncePerRequestFilter {
    private final String release;

    public AwsHealthReleaseHeader(@Value("${DEPLOY_RELEASE_ID}") String release) {
        if (!release.matches("[0-9]+-[0-9]+")) {
            throw new IllegalArgumentException("Expected GitHub run ID and attempt");
        }
        this.release = release;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/actuator/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Algorhythm-Release", release);
        chain.doFilter(request, response);
    }
}
