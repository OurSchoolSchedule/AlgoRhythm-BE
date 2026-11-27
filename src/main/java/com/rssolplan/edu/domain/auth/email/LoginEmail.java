package com.rssolplan.edu.domain.auth.email;

import com.rssolplan.edu.domain.roster.TeacherRosterService;
import com.rssolplan.edu.global.exception.BadRequestException;
import com.rssolplan.edu.global.exception.ForbiddenException;

import java.util.Set;

/** Public authentication policy, independent of mutable profile/contact addresses. */
public final class LoginEmail {
    private static final Set<String> DOMAINS = Set.of("ewha.ac.kr", "gmail.com");
    public static final String MESSAGE =
            "현재 서비스는 이화여자대학교 이메일(ewha.ac.kr)과 Gmail(gmail.com)만 지원합니다.";

    private LoginEmail() {}

    public static String require(String value) {
        String address = TeacherRosterService.normalizeEmail(value);
        if (!DOMAINS.contains(address.substring(address.indexOf('@') + 1))) throw new Denied();
        return address;
    }

    public static boolean allowed(String value) {
        try {
            require(value);
            return true;
        } catch (BadRequestException | Denied e) {
            return false;
        }
    }

    public static class Denied extends ForbiddenException {
        public Denied() { super(MESSAGE); }
    }
}
