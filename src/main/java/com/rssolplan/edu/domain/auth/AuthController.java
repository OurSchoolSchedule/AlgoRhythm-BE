package com.rssolplan.edu.domain.auth;

import com.rssolplan.edu.domain.auth.passkey.PasskeyRealm;
import com.rssolplan.edu.domain.auth.dto.LoginResponse;
import com.rssolplan.edu.domain.auth.dto.RefreshTokenResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasskeyRealm realm;

    // 카카오 OAuth2 Redirect 콜백 - 프론트에서 받은 code를 백엔드가 처리
    @GetMapping("/kakao/callback")
    public void kakaoCallback(
            @RequestParam("code") String code,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            HttpServletResponse response
    ) throws IOException {

        String callback = realm.origin() + "/auth/kakao/callback";
        if (redirectUri != null && !redirectUri.isBlank() && !callback.equals(redirectUri)) {
            throw new IllegalArgumentException("허용되지 않은 복귀 주소입니다.");
        }
        LoginResponse loginResponse = authService.handleKakaoCallback(code);
        String targetUrl = callback
                + "?accessToken=" + loginResponse.getAccessToken()
                + "&refreshToken=" + loginResponse.getRefreshToken()
                + "&userId=" + loginResponse.getUserId();

        response.sendRedirect(targetUrl);
    }


    // Refresh Token으로 Access Token 재발급
    @PostMapping("/refresh-token")
    public ResponseEntity<RefreshTokenResponse> refresh(@RequestHeader("Authorization") String bearer) {
        String refreshToken = bearer.replace("Bearer ", "");
        return ResponseEntity.ok(authService.refresh(refreshToken));
    }

    // 로그아웃
    @PostMapping("/logout")
    public ResponseEntity<String> logout(@AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return ResponseEntity.ok("로그아웃 성공");
    }
}
