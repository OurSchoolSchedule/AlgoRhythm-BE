package com.rssolplan.edu.domain.mypage.impl;

import com.rssolplan.edu.domain.mypage.MypageService;
import com.rssolplan.edu.domain.mypage.dto.*;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class MypageServiceImpl implements MypageService {

    private static final String REDIS_ROLE_KEY_PREFIX = "auth:role:";

    private final UserRepository users;
    private final SchoolRepository schools;
    private final SchoolUserRepository schoolUsers;
    private final StringRedisTemplate redisTemplate;

    // ===== 헬퍼 =====

    private SchoolUser ensureMapping(Long userId, Long schoolId) {
        return schoolUsers.findByUser_IdAndSchool_Id(userId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("해당 학교에 소속되지 않은 사용자입니다."));
    }

    private SchoolUser resolveActiveMappingOrDefault(Long userId) {
        User u = users.findById(userId).orElseThrow();
        if (u.getActiveSchoolId() != null) {
            return ensureMapping(userId, u.getActiveSchoolId());
        }
        return schoolUsers.findFirstByUser_IdOrderByCreatedAtAsc(userId)
                .orElseThrow(() -> new IllegalArgumentException("등록된 학교가 없습니다."));
    }

    private void evictRoleCache(Long userId) {
        redisTemplate.delete(REDIS_ROLE_KEY_PREFIX + userId);
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
        ensureMapping(userId, schoolId);
        u.setActiveSchoolId(schoolId);
        users.save(u);
        evictRoleCache(userId);
        return toActiveSchoolResponse(ensureMapping(userId, schoolId));
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
        User admin = users.findById(adminId).orElseThrow();

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

        if (admin.getActiveSchoolId() == null) {
            admin.setActiveSchoolId(school.getId());
            users.save(admin);
            evictRoleCache(admin.getId());
        }

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
                    .findFirst()
                    .map(s -> s.getSchool().getId())
                    .orElse(null);
            u.setActiveSchoolId(nextActive);
            users.save(u);
            evictRoleCache(adminId);
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
        return schoolUsers.findByUser_IdAndPosition(teacherId, Position.TEACHER)
                .stream()
                .sorted(Comparator.comparing(su -> su.getSchool().getId()))
                .map(this::toSchoolSimple)
                .toList();
    }

    @Override
    public SchoolSimpleResponse joinTeacherSchool(Long teacherId, TeacherJoinSchoolRequest req) {
        User teacher = users.findById(teacherId).orElseThrow();
        School school = schools.findBySchoolCode(req.getSchoolCode())
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 학교 코드입니다."));

        if (schoolUsers.existsByUser_IdAndSchool_Id(teacherId, school.getId())) {
            throw new IllegalArgumentException("이미 등록된 학교입니다.");
        }

        SchoolUser link = SchoolUser.builder()
                .user(teacher).school(school)
                .position(Position.TEACHER)
                .employmentStatus(EmploymentStatus.HIRED)
                .hireDate(req.getHireDate())
                .build();
        schoolUsers.save(link);

        if (teacher.getActiveSchoolId() == null) {
            teacher.setActiveSchoolId(school.getId());
            users.save(teacher);
            evictRoleCache(teacher.getId());
        }

        return toSchoolSimple(link);
    }

    @Override
    public void leaveTeacherSchool(Long teacherId, Long schoolId) {
        SchoolUser su = ensureMapping(teacherId, schoolId);
        if (su.getPosition() != Position.TEACHER) {
            throw new IllegalArgumentException("교사 권한이 필요한 요청입니다.");
        }
        schoolUsers.delete(su);

        User u = users.findById(teacherId).orElseThrow();
        if (schoolId.equals(u.getActiveSchoolId())) {
            Long nextActive = schoolUsers.findByUser_Id(teacherId).stream()
                    .findFirst()
                    .map(s -> s.getSchool().getId())
                    .orElse(null);
            u.setActiveSchoolId(nextActive);
            users.save(u);
            evictRoleCache(teacherId);
        }
    }
}
