package com.rssolplan.edu.domain.roster;

import com.rssolplan.edu.domain.auth.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

import static com.rssolplan.edu.domain.roster.TeacherRosterService.*;

@RestController
@RequestMapping("/api/schools/{schoolId}/roster")
@RequiredArgsConstructor
public class TeacherRosterController {
    private final TeacherRosterService roster;
    private final RosterMembershipService memberships;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Entry register(@AuthenticationPrincipal Long userId, @PathVariable Long schoolId,
                          @RequestBody IdentityInput identity) {
        return roster.register(userId, schoolId, identity);
    }

    @GetMapping
    public List<Entry> list(@AuthenticationPrincipal Long userId, @PathVariable Long schoolId,
                            @RequestParam(required = false) Long termId) {
        return roster.list(userId, schoolId, termId);
    }

    @GetMapping("/me")
    public Entry self(@AuthenticationPrincipal Long userId, @PathVariable Long schoolId,
                      @RequestParam(required = false) Long termId) {
        return roster.self(userId, schoolId, termId);
    }

    @GetMapping("/{rosterId}")
    public Entry get(@AuthenticationPrincipal Long userId, @PathVariable Long schoolId,
                     @PathVariable Long rosterId, @RequestParam(required = false) Long termId) {
        return roster.get(userId, schoolId, rosterId, termId);
    }

    @PutMapping("/{rosterId}")
    public Entry edit(@AuthenticationPrincipal Long userId, @PathVariable Long schoolId,
                      @PathVariable Long rosterId, @RequestBody IdentityInput identity) {
        return roster.updateIdentity(userId, schoolId, rosterId, identity);
    }

    @PutMapping("/{rosterId}/terms/{termId}")
    public Entry replaceAssignment(@AuthenticationPrincipal Long userId, @PathVariable Long schoolId,
                                   @PathVariable Long rosterId, @PathVariable Long termId,
                                   @RequestBody AssignmentInput assignment) {
        return roster.replaceAssignment(userId, schoolId, rosterId, termId, assignment);
    }

    @PostMapping("/{rosterId}/approve")
    public Entry approve(@AuthenticationPrincipal Long userId, @PathVariable Long schoolId,
                         @PathVariable Long rosterId) {
        memberships.approve(userId, schoolId, rosterId);
        return roster.get(userId, schoolId, rosterId, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> invalidInput() {
        return ApiResponse.error("BAD_REQUEST", "요청 본문 또는 ID 형식을 확인해 주세요.");
    }
}
