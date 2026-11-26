package com.rssolplan.edu.domain.roster;

import com.rssolplan.edu.global.exception.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.text.Normalizer;

public final class SchoolNamePlan {
    record Row(long id, String display, String base) {}
    public record Proposal(long schoolId, String schoolName, String baseName, String displayName,
                           String renamedFrom, String renamedTo, String confirmation) {}
    final Proposal proposal;
    final Row rename;
    final boolean replay;

    private SchoolNamePlan(long school, String schoolName, long user, String base, String display,
                           Row rename, String renamedTo, boolean replay) {
        this.rename = rename;
        this.replay = replay;
        // This is an optimistic version, not an authorization token. Recompute it under the school lock.
        var fields = List.of(school, user, base, display, rename == null ? 0L : rename.id(),
                Objects.toString(renamedTo, ""));
        StringBuilder versionInput = new StringBuilder();
        for (Object field : fields) {
            String value = field.toString();
            versionInput.append(value.length()).append(':').append(value);
        }
        String version = UUID.nameUUIDFromBytes(versionInput.toString().getBytes(StandardCharsets.UTF_8)).toString();
        proposal = new Proposal(school, schoolName, base, display,
                rename == null ? null : rename.display(), renamedTo, version);
    }

    static SchoolNamePlan build(long school, String schoolName, long user, String input, Long own,
                                List<Row> rows, Set<String> legacyNames) {
        String base = Normalizer.normalize(TeacherRosterService.validateDisplayName(input), Normalizer.Form.NFC);
        Set<String> used = new HashSet<>(legacyNames);
        Row rename = null;
        boolean group = used.contains(base);
        boolean reserveFirst = group;
        for (Row row : rows) {
            if (Objects.equals(row.id(), own)) {
                if (base.equals(row.base()))
                    return new SchoolNamePlan(school, schoolName, user, base, row.display(), null, null, true);
                continue;
            }
            used.add(row.display());
            if (base.equals(row.base()) || base.equals(row.display())) group = true;
            if (base.equals(row.display())) {
                if (base.equals(row.base())) rename = row;
                // Defer changes to unconfirmed names. The earliest existing row can claim A.
                else if (row.base() == null && (own == null || row.id() < own)) reserveFirst = true;
            }
        }
        String display = base, renamedTo = null;
        if (group) {
            if (reserveFirst) used.add(withSuffix(base, 1));
            if (rename != null) {
                renamedTo = available(base, used);
                used.add(renamedTo);
            }
            display = available(base, used);
        }
        return new SchoolNamePlan(school, schoolName, user, base, display, rename, renamedTo, false);
    }

    void requireConfirmation(String confirmation) {
        if (!replay && (confirmation != null || !proposal.baseName().equals(proposal.displayName()))
                && !proposal.confirmation().equals(confirmation)) throw new Changed(proposal);
    }

    private static String available(String base, Set<String> used) {
        for (int i = 1; ; i++) {
            String name = withSuffix(base, i);
            if (!used.contains(name)) return name;
        }
    }

    private static String withSuffix(String base, int n) {
        StringBuilder suffix = new StringBuilder();
        for (; n > 0; n = (n - 1) / 26) suffix.append((char) ('A' + (n - 1) % 26));
        if (base.codePointCount(0, base.length()) + suffix.length() > 100)
            throw new BadRequestException("알파벳을 붙인 학교 표시 이름이 100자를 넘습니다. 이름을 수정해 주세요.");
        return base + suffix.reverse();
    }

    static final class Changed extends RuntimeException {
        final Proposal proposal;
        Changed(Proposal proposal) { super("학교의 동명이인 현황을 확인하고 표시 이름을 확정해 주세요."); this.proposal = proposal; }
    }

    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    @RestControllerAdvice
    static class Advice {
        record Conflict(String code, String message, Proposal proposal) {}
        @ResponseStatus(HttpStatus.CONFLICT)
        @ExceptionHandler(Changed.class)
        Conflict changed(Changed error) {
            return new Conflict("NAME_CONFIRMATION_REQUIRED", error.getMessage(), error.proposal);
        }
    }
}
