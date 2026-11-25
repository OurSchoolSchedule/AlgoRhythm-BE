package com.rssolplan.edu.domain.onboarding;

import com.rssolplan.edu.domain.onboarding.dto.OnboardingRequest;
import com.rssolplan.edu.domain.onboarding.dto.OnboardingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;
    @PostMapping
    public ResponseEntity<OnboardingResponse> onboarding(
            @AuthenticationPrincipal Long userId,
            @RequestBody OnboardingRequest req
    ) {
        OnboardingResponse result = onboardingService.onboard(userId, req);
        return ResponseEntity.status("PENDING".equals(result.getEmploymentStatus()) ? 202 : 200).body(result);
    }
}
