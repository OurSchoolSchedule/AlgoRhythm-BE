package com.rssolplan.edu.domain.onboarding;

import com.rssolplan.edu.domain.onboarding.dto.OnboardingRequest;
import com.rssolplan.edu.domain.onboarding.dto.OnboardingResponse;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserProfileService;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.fordevToken.SchoolCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final String REDIS_ROLE_KEY_PREFIX = "auth:role:";

    private final UserRepository users;
    private final SchoolRepository schools;
    private final SchoolUserRepository schoolUsers;
    private final UserProfileService userProfileService;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public OnboardingResponse onboard(Long userId, OnboardingRequest req) {
        User user = users.findById(userId).orElseThrow();
        if (req.getRole() == null) {
            throw new IllegalArgumentException("역할(role)이 필요합니다. (ADMIN 또는 TEACHER)");
        }
        String role = req.getRole().toUpperCase(java.util.Locale.ROOT);

        School school;
        if ("ADMIN".equals(role)) {
            school = School.builder()
                    .schoolCode(SchoolCodeGenerator.generate())
                    .name(req.getName())
                    .address(req.getAddress())
                    .phoneNumber(req.getPhoneNumber())
                    .build();
            schools.save(school);

        } else if ("TEACHER".equals(role)) {
            school = schools.findBySchoolCode(req.getSchoolCode())
                    .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 학교 코드입니다."));
        } else {
            throw new IllegalArgumentException("유효하지 않은 역할: " + req.getRole());
        }

        SchoolUser.Position position = SchoolUser.Position.valueOf(role);
        SchoolUser link = SchoolUser.builder()
                .user(user)
                .school(school)
                .position(position)
                .employmentStatus(SchoolUser.EmploymentStatus.HIRED)
                .hireDate(req.getHireDate())
                .build();
        schoolUsers.save(link);

        user.setActiveSchoolId(school.getId());
        users.save(user);

        // Evict role cache so next request reflects the new role
        redisTemplate.delete(REDIS_ROLE_KEY_PREFIX + userId);

        userProfileService.updateDefaultImageForRole(user, role);

        return new OnboardingResponse(
                user.getId(),
                link.getId(),
                school.getId(),
                role,
                "HIRED",
                school.getSchoolCode(),
                school.getName(),
                school.getAddress(),
                school.getPhoneNumber(),
                link.getHireDate()
        );
    }
}
