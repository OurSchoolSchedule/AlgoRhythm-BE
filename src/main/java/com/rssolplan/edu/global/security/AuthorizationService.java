package com.rssolplan.edu.global.security;

import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {

    private final UserRepository userRepository;
    private final SchoolUserRepository schoolUserRepository;

    public AuthorizationService(UserRepository userRepository,
                                SchoolUserRepository schoolUserRepository) {
        this.userRepository = userRepository;
        this.schoolUserRepository = schoolUserRepository;
    }

    public Long getActiveSchoolIdOrThrow(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("존재하지 않는 사용자입니다."));
        Long activeSchoolId = user.getActiveSchoolId();
        if (activeSchoolId == null) {
            throw new ForbiddenException("현재 활성화된 학교가 없습니다.");
        }
        return activeSchoolId;
    }

    public SchoolUser getSchoolUserOrThrow(Long userId, Long schoolId) {
        return schoolUserRepository.findByUser_IdAndSchool_Id(userId, schoolId)
                .orElseThrow(() -> new ForbiddenException("해당 학교에 소속된 교직원이 아닙니다."));
    }
}
