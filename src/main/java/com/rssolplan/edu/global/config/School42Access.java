package com.rssolplan.edu.global.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/** Team rehearsal credentials authorize only endpoints in the School42 namespace. */
public class School42Access {
    public static final String ROLE = "school42.rehearsal.role";
    private final String adminHash;
    private final String teacherHash;

    public School42Access(@Value("${school42.admin-hash:}") String adminHash,
                          @Value("${school42.teacher-hash:}") String teacherHash) {
        this.adminHash = adminHash;
        this.teacherHash = teacherHash;
    }

    public AuthorizationDecision check(Supplier<Authentication> unused, RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("School42 ") || header.length() > 120)
            return new AuthorizationDecision(false);
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(header.substring(9).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        boolean admin = matches(adminHash, digest);
        boolean teacher = matches(teacherHash, digest);
        boolean teacherRead = "GET".equals(request.getMethod()) &&
                ("/api/school42/status".equals(request.getRequestURI()) ||
                 "/api/school42/week".equals(request.getRequestURI()));
        if (!admin && !(teacher && teacherRead)) return new AuthorizationDecision(false);
        request.setAttribute(ROLE, admin ? "ADMIN" : "TEACHER");
        return new AuthorizationDecision(true);
    }

    private static boolean matches(String expected, String actual) {
        return expected.length() == 64 && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }
}
