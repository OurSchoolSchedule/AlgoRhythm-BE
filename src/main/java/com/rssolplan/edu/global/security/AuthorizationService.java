package com.rssolplan.edu.global.security;

import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final UserRepository userRepository;
    private final SchoolUserRepository schoolUserRepository;

    public Long getActiveSchoolIdOrThrow(Long userId) {
        return userRepository.findActiveMembership(userId, SchoolUser.EmploymentStatus.HIRED)
                .orElseThrow(() -> new ForbiddenException("재직 중인 활성 학교가 없습니다."))
                .getSchool().getId();
    }

    public SchoolUser getSchoolUserOrThrow(Long userId, Long schoolId) {
        return schoolUserRepository.findByUser_IdAndSchool_Id(userId, schoolId)
                .filter(member -> member.getEmploymentStatus() == SchoolUser.EmploymentStatus.HIRED)
                .orElseThrow(() -> new ForbiddenException("해당 학교에 재직 중인 교직원이 아닙니다."));
    }
}
