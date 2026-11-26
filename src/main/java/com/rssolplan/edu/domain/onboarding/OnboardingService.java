package com.rssolplan.edu.domain.onboarding;

import com.rssolplan.edu.domain.onboarding.dto.OnboardingRequest;
import com.rssolplan.edu.domain.roster.RosterMembershipService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final UserRepository users;
    private final SchoolRepository schools;
    private final SchoolUserRepository schoolUsers;
    private final UserProfileService userProfileService;
    private final RosterMembershipService memberships;

    @Transactional
    public OnboardingResponse onboard(Long userId, OnboardingRequest req) {
        if (req.getRole() == null) {
            throw new IllegalArgumentException("역할(role)이 필요합니다. (ADMIN 또는 TEACHER)");
        }
        String role = req.getRole().toUpperCase(java.util.Locale.ROOT);

        if ("TEACHER".equals(role)) {
            return memberships.requestSchool(userId, req.getSchoolCode(), req.getDisplayName(), req.getHireDate(), req.getNameConfirmation());
        }
        if (!"ADMIN".equals(role) || (req.getSchoolCode() != null && !req.getSchoolCode().isBlank())) {
            throw new IllegalArgumentException("기존 학교는 학교 코드로 교사 가입을 요청하세요.");
        }

        User user = users.findById(userId).orElseThrow();
        School school = schools.save(School.builder()
                .schoolCode(SchoolCodeGenerator.generate())
                .name(req.getName())
                .address(req.getAddress())
                .phoneNumber(req.getPhoneNumber())
                .build());
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

        if (req.getDisplayName() != null) {
            users.flush();
            memberships.requestSchool(userId, school.getSchoolCode(), req.getDisplayName(), req.getHireDate(), null);
        }
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
