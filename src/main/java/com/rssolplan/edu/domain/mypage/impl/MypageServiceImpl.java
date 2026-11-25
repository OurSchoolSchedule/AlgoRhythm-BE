package com.rssolplan.edu.domain.mypage.impl;

import com.rssolplan.edu.domain.mypage.MypageService;
import com.rssolplan.edu.domain.onboarding.dto.OnboardingResponse;
import com.rssolplan.edu.domain.roster.RosterMembershipService;
import com.rssolplan.edu.domain.mypage.dto.*;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUser.EmploymentStatus;
import com.rssolplan.edu.domain.school.SchoolUser.Position;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.fordevToken.SchoolCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MypageServiceImpl implements MypageService {

    private final UserRepository users;
    private final SchoolRepository schools;
    private final SchoolUserRepository schoolUsers;
    private final RosterMembershipService memberships;

    // ===== 헬퍼 =====

    private SchoolUser ensureMapping(Long userId, Long schoolId) {
        return schoolUsers.findByUser_IdAndSchool_Id(userId, schoolId)
                .orElseThrow(() -> new ForbiddenException("해당 학교에 소속되지 않은 사용자입니다."));
    }

    private SchoolUser resolveActiveMappingOrDefault(Long userId) {
        User u = users.findById(userId).orElseThrow();
        // getActiveSchoolIdOrThrow와 동일한 실패 조건을 유지한다.
        // activeSchoolId가 null이면 @OwnerOnly/학교 API도 거부되므로 여기서도 동일하게 거부한다.
        if (u.getActiveSchoolId() == null) {
            throw new ForbiddenException("활성 학교가 설정되어 있지 않습니다. 활성 학교를 먼저 설정하세요.");
        }
        return ensureHired(userId, u.getActiveSchoolId());
    }

    private SchoolUser ensureHired(Long userId, Long schoolId) {
        SchoolUser member = ensureMapping(userId, schoolId);
        if (member.getEmploymentStatus() != EmploymentStatus.HIRED)
            throw new ForbiddenException("재직 중인 학교만 선택할 수 있습니다.");
        return member;
    }

    private ActiveSchoolResponse toActiveSchoolResponse(SchoolUser su) {
        School s = su.getSchool();
        return ActiveSchoolResponse.builder()
                .schoolId(s.getId())
                .schoolCode(s.getSchoolCode())
                .name(s.getName())
                .address(s.getAddress())
                .phoneNumber(s.getPhoneNumber())
                .position(su.getPosition().name())
                .employmentStatus(su.getEmploymentStatus().name())
                .build();
    }

    private SchoolSimpleResponse toSchoolSimple(SchoolUser su) {
        School s = su.getSchool();
        return SchoolSimpleResponse.builder()
                .schoolId(s.getId())
                .schoolCode(s.getSchoolCode())
                .name(s.getName())
                .address(s.getAddress())
                .phoneNumber(s.getPhoneNumber())
                .position(su.getPosition().name())
                .employmentStatus(su.getEmploymentStatus().name())
                .hireDate(su.getHireDate())
                .build();
    }

    // ===== 활성 학교 =====

    @Override
    @Transactional(readOnly = true)
    public ActiveSchoolResponse getActiveSchool(Long userId) {
        return toActiveSchoolResponse(resolveActiveMappingOrDefault(userId));
    }

    @Override
    public ActiveSchoolResponse updateActiveSchool(Long userId, Long schoolId) {
        User u = users.findById(userId).orElseThrow();
        SchoolUser member = ensureHired(userId, schoolId);
        u.setActiveSchoolId(schoolId);
        users.save(u);
        return toActiveSchoolResponse(member);
    }

    // ===== 교감/교장(Admin) =====

    @Override
    @Transactional(readOnly = true)
    public AdminProfileResponse getAdminProfile(Long adminId) {
        SchoolUser su = resolveActiveMappingOrDefault(adminId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        User u = su.getUser();
        return AdminProfileResponse.builder()
                .userId(u.getId())
                .username(u.getUsername())
                .email(u.getEmail())
                .profileImageUrl(u.getProfileImageUrl())
                .position(su.getPosition().name())
                .employmentStatus(su.getEmploymentStatus().name())
                .build();
    }

    @Override
    public AdminProfileResponse updateAdminProfile(Long adminId, AdminProfileUpdateRequest req) {
        SchoolUser su = resolveActiveMappingOrDefault(adminId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        User u = su.getUser();
        if (req.getUsername() != null) u.setUsername(req.getUsername());
        if (req.getEmail() != null) u.setEmail(req.getEmail());
        users.save(u);
        return getAdminProfile(adminId);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminSchoolResponse getAdminActiveSchool(Long adminId) {
        SchoolUser su = resolveActiveMappingOrDefault(adminId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        School s = su.getSchool();
        return AdminSchoolResponse.builder()
                .schoolId(s.getId())
                .schoolCode(s.getSchoolCode())
                .name(s.getName())
                .address(s.getAddress())
                .phoneNumber(s.getPhoneNumber())
                .build();
    }

    @Override
    public AdminSchoolResponse updateAdminActiveSchool(Long adminId, AdminSchoolUpdateRequest req) {
        SchoolUser su = resolveActiveMappingOrDefault(adminId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        School s = su.getSchool();
        if (req.getName() != null) s.setName(req.getName());
        if (req.getAddress() != null) s.setAddress(req.getAddress());
        if (req.getPhoneNumber() != null) s.setPhoneNumber(req.getPhoneNumber());
        schools.save(s);
        return getAdminActiveSchool(adminId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SchoolSimpleResponse> listAdminSchools(Long adminId) {
        return schoolUsers.findByUser_IdAndPosition(adminId, Position.ADMIN)
                .stream()
                .sorted(Comparator.comparing(su -> su.getSchool().getId()))
                .map(this::toSchoolSimple)
                .toList();
    }

    @Override
    public SchoolSimpleResponse addAdminSchool(Long adminId, AdminCreateSchoolRequest req) {
        SchoolUser current = resolveActiveMappingOrDefault(adminId);
        if (current.getPosition() != Position.ADMIN) throw new ForbiddenException("관리자 권한이 필요합니다.");
        User admin = current.getUser();

        School school = School.builder()
                .schoolCode(SchoolCodeGenerator.generate())
                .name(req.getName())
                .address(req.getAddress())
                .phoneNumber(req.getPhoneNumber())
                .build();
        schools.save(school);

        SchoolUser link = SchoolUser.builder()
                .user(admin).school(school)
                .position(Position.ADMIN)
                .employmentStatus(EmploymentStatus.HIRED)
                .hireDate(req.getHireDate())
                .build();
        schoolUsers.save(link);

        return toSchoolSimple(link);
    }

    @Override
    public void removeAdminSchool(Long adminId, Long schoolId) {
        SchoolUser su = ensureMapping(adminId, schoolId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        schoolUsers.delete(su);

        User u = users.findById(adminId).orElseThrow();
        if (schoolId.equals(u.getActiveSchoolId())) {
            Long nextActive = schoolUsers.findByUser_Id(adminId).stream()
                    .filter(s -> s.getEmploymentStatus() == EmploymentStatus.HIRED)
                    .findFirst()
                    .map(s -> s.getSchool().getId())
                    .orElse(null);
            u.setActiveSchoolId(nextActive);
            users.save(u);
        }
    }

    // ===== 교사(Teacher) =====

    @Override
    @Transactional(readOnly = true)
    public TeacherProfileResponse getTeacherProfile(Long teacherId) {
        SchoolUser su = resolveActiveMappingOrDefault(teacherId);
        if (su.getPosition() != Position.TEACHER) {
            throw new IllegalArgumentException("교사 권한이 필요한 요청입니다.");
        }
        User u = su.getUser();
        School s = su.getSchool();
        return TeacherProfileResponse.builder()
                .userId(u.getId())
                .username(u.getUsername())
                .email(u.getEmail())
                .profileImageUrl(u.getProfileImageUrl())
                .position(su.getPosition().name())
                .employmentStatus(su.getEmploymentStatus().name())
                .currentSchool(TeacherProfileResponse.CurrentSchool.builder()
                        .schoolId(s.getId())
                        .name(s.getName())
                        .schoolCode(s.getSchoolCode())
                        .build())
                .build();
    }

    @Override
    public TeacherProfileResponse updateTeacherProfile(Long teacherId, TeacherProfileUpdateRequest req) {
        SchoolUser su = resolveActiveMappingOrDefault(teacherId);
        if (su.getPosition() != Position.TEACHER) {
            throw new IllegalArgumentException("교사 권한이 필요한 요청입니다.");
        }
        User u = su.getUser();
        if (req.getUsername() != null) u.setUsername(req.getUsername());
        if (req.getEmail() != null) u.setEmail(req.getEmail());
        users.save(u);
        return getTeacherProfile(teacherId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SchoolSimpleResponse> listTeacherSchools(Long teacherId) {
        var members = schoolUsers.findByUser_IdAndPosition(teacherId, Position.TEACHER)
                .stream().map(this::toSchoolSimple);
        var pending = memberships.pendingSchools(teacherId).stream().map(this::toSchoolSimple);
        return java.util.stream.Stream.concat(members, pending)
                .sorted(Comparator.comparing(SchoolSimpleResponse::getSchoolId)).toList();
    }

    @Override
    public SchoolSimpleResponse joinTeacherSchool(Long teacherId, TeacherJoinSchoolRequest req) {
        return toSchoolSimple(memberships.requestSchool(teacherId, req.getSchoolCode(),
                req.getDisplayName(), req.getHireDate()));
    }

    private SchoolSimpleResponse toSchoolSimple(OnboardingResponse result) {
        return SchoolSimpleResponse.builder()
                .schoolId(result.getSchoolId()).schoolCode(result.getSchoolCode()).name(result.getSchoolName())
                .address(result.getAddress()).phoneNumber(result.getPhoneNumber())
                .position(result.getPosition()).employmentStatus(result.getEmploymentStatus())
                .hireDate(result.getHireDate()).build();
    }

    @Override
    public void leaveTeacherSchool(Long teacherId, Long schoolId) {
        SchoolUser su = ensureMapping(teacherId, schoolId);
        if (su.getPosition() != Position.TEACHER) {
            throw new IllegalArgumentException("교사 권한이 필요한 요청입니다.");
        }
        su.setEmploymentStatus(EmploymentStatus.RESIGNED);

        User u = users.findById(teacherId).orElseThrow();
        if (schoolId.equals(u.getActiveSchoolId())) {
            Long nextActive = schoolUsers.findByUser_Id(teacherId).stream()
                    .filter(s -> s.getEmploymentStatus() == EmploymentStatus.HIRED)
                    .findFirst()
                    .map(s -> s.getSchool().getId())
                    .orElse(null);
            u.setActiveSchoolId(nextActive);
            users.save(u);
        }
    }
}
