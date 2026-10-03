package com.rssolplan.edu.global.security.aspect;

import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.security.AuthorizationService;
import com.rssolplan.edu.global.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class OwnerOnlyAspect {

    private final AuthorizationService service;

    /**
     * Requires an authenticated user with an ADMIN membership in the active school
     * before an {@code @OwnerOnly} method runs.
     *
     * @throws ForbiddenException if authentication, active school, membership, or administrator access is missing
     * @throws com.rssolplan.edu.global.exception.NotFoundException if the authenticated user does not exist
     */
    @Before("@annotation(com.rssolplan.edu.global.security.annotation.OwnerOnly)")
    public void checkOwner() {
        Long userId = SecurityUtil.getCurrentUserId();
        if (userId == null) {
            throw new ForbiddenException("로그인이 필요합니다.");
        }

        Long activeSchoolId = service.getActiveSchoolIdOrThrow(userId);
        SchoolUser requester = service.getSchoolUserOrThrow(userId, activeSchoolId);

        if (requester.getPosition() != SchoolUser.Position.ADMIN) {
            throw new ForbiddenException("관리자(교감/교장) 권한이 필요합니다.");
        }
    }
}
