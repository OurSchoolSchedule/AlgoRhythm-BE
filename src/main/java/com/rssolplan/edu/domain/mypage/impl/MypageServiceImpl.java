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

    // 헬퍼

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

    private ActiveStoreResponse toActiveStoreResponse(SchoolUser su) {
        School s = su.getSchool();
        return ActiveStoreResponse.builder()
                .schoolId(s.getId())
                .schoolCode(s.getSchoolCode())
                .name(s.getName())
                .address(s.getAddress())
                .phoneNumber(s.getPhoneNumber())
                .position(su.getPosition().name())
                .employmentStatus(su.getEmploymentStatus().name())
                .build();
    }

    private StoreSimpleResponse toSchoolSimple(SchoolUser su) {
        School s = su.getSchool();
        return StoreSimpleResponse.builder()
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

    // 활성 학교

    @Override
    @Transactional(readOnly = true)
    public ActiveStoreResponse getActiveStore(Long userId) {
        return toActiveStoreResponse(resolveActiveMappingOrDefault(userId));
    }

    @Override
    public ActiveStoreResponse updateActiveStore(Long userId, Long schoolId) {
        User u = users.findById(userId).orElseThrow();
        ensureMapping(userId, schoolId);
        u.setActiveSchoolId(schoolId);
        users.save(u);
        evictRoleCache(userId);
        return toActiveStoreResponse(ensureMapping(userId, schoolId));
    }

    // 교감/교장(Admin) 관련

    @Override
    @Transactional(readOnly = true)
    public OwnerProfileResponse getOwnerProfile(Long ownerId) {
        SchoolUser su = resolveActiveMappingOrDefault(ownerId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        User u = su.getUser();
        return OwnerProfileResponse.builder()
                .userId(u.getId())
                .username(u.getUsername())
                .email(u.getEmail())
                .profileImageUrl(u.getProfileImageUrl())
                .position(su.getPosition().name())
                .employmentStatus(su.getEmploymentStatus().name())
                .build();
    }

    @Override
    public OwnerProfileResponse updateOwnerProfile(Long ownerId, OwnerProfileUpdateRequest req) {
        SchoolUser su = resolveActiveMappingOrDefault(ownerId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        User u = su.getUser();
        if (req.getUsername() != null) u.setUsername(req.getUsername());
        if (req.getEmail() != null) u.setEmail(req.getEmail());
        users.save(u);
        return getOwnerProfile(ownerId);
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerStoreResponse getOwnerActiveStore(Long ownerId) {
        SchoolUser su = resolveActiveMappingOrDefault(ownerId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        School s = su.getSchool();
        return OwnerStoreResponse.builder()
                .schoolId(s.getId())
                .schoolCode(s.getSchoolCode())
                .name(s.getName())
                .address(s.getAddress())
                .phoneNumber(s.getPhoneNumber())
                .build();
    }

    @Override
    public OwnerStoreResponse updateOwnerActiveStore(Long ownerId, OwnerStoreUpdateRequest req) {
        SchoolUser su = resolveActiveMappingOrDefault(ownerId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        School s = su.getSchool();
        if (req.getName() != null) s.setName(req.getName());
        if (req.getAddress() != null) s.setAddress(req.getAddress());
        if (req.getPhoneNumber() != null) s.setPhoneNumber(req.getPhoneNumber());
        schools.save(s);
        return getOwnerActiveStore(ownerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreSimpleResponse> listOwnerStores(Long ownerId) {
        return schoolUsers.findByUser_IdAndPosition(ownerId, Position.ADMIN)
                .stream()
                .sorted(Comparator.comparing(su -> su.getSchool().getId()))
                .map(this::toSchoolSimple)
                .toList();
    }

    @Override
    public StoreSimpleResponse addOwnerStore(Long ownerId, OwnerCreateStoreRequest req) {
        User owner = users.findById(ownerId).orElseThrow();

        School school = School.builder()
                .schoolCode(SchoolCodeGenerator.generate())
                .name(req.getName())
                .address(req.getAddress())
                .phoneNumber(req.getPhoneNumber())
                .build();
        schools.save(school);

        SchoolUser link = SchoolUser.builder()
                .user(owner).school(school)
                .position(Position.ADMIN)
                .employmentStatus(EmploymentStatus.HIRED)
                .hireDate(req.getHireDate())
                .build();
        schoolUsers.save(link);

        return toSchoolSimple(link);
    }

    @Override
    public void removeOwnerStore(Long ownerId, Long schoolId) {
        SchoolUser su = ensureMapping(ownerId, schoolId);
        if (su.getPosition() != Position.ADMIN) {
            throw new IllegalArgumentException("관리자(교감/교장) 권한이 필요한 요청입니다.");
        }
        schoolUsers.delete(su);

        User u = users.findById(ownerId).orElseThrow();
        if (schoolId.equals(u.getActiveSchoolId())) {
            Long nextActive = schoolUsers.findByUser_Id(ownerId).stream()
                    .findFirst()
                    .map(s -> s.getSchool().getId())
                    .orElse(null);
            u.setActiveSchoolId(nextActive);
            users.save(u);
            evictRoleCache(ownerId);
        }
    }

    // 교사(Teacher) 관련

    @Override
    @Transactional(readOnly = true)
    public StaffProfileResponse getStaffProfile(Long staffId) {
        SchoolUser su = resolveActiveMappingOrDefault(staffId);
        if (su.getPosition() != Position.TEACHER) {
            throw new IllegalArgumentException("교사 권한이 필요한 요청입니다.");
        }
        User u = su.getUser();
        School s = su.getSchool();
        return StaffProfileResponse.builder()
                .userId(u.getId())
                .username(u.getUsername())
                .email(u.getEmail())
                .profileImageUrl(u.getProfileImageUrl())
                .position(su.getPosition().name())
                .employmentStatus(su.getEmploymentStatus().name())
                .currentSchool(StaffProfileResponse.CurrentSchool.builder()
                        .schoolId(s.getId())
                        .name(s.getName())
                        .schoolCode(s.getSchoolCode())
                        .build())
                .build();
    }

    @Override
    public StaffProfileResponse updateStaffProfile(Long staffId, StaffProfileUpdateRequest req) {
        SchoolUser su = resolveActiveMappingOrDefault(staffId);
        if (su.getPosition() != Position.TEACHER) {
            throw new IllegalArgumentException("교사 권한이 필요한 요청입니다.");
        }
        User u = su.getUser();
        if (req.getUsername() != null) u.setUsername(req.getUsername());
        if (req.getEmail() != null) u.setEmail(req.getEmail());
        users.save(u);
        return getStaffProfile(staffId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreSimpleResponse> listStaffStores(Long staffId) {
        return schoolUsers.findByUser_IdAndPosition(staffId, Position.TEACHER)
                .stream()
                .sorted(Comparator.comparing(su -> su.getSchool().getId()))
                .map(this::toSchoolSimple)
                .toList();
    }

    @Override
    public StoreSimpleResponse joinStaffStore(Long staffId, StaffJoinStoreRequest req) {
        User teacher = users.findById(staffId).orElseThrow();
        School school = schools.findBySchoolCode(req.getSchoolCode())
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 학교 코드입니다."));

        if (schoolUsers.existsByUser_IdAndSchool_Id(staffId, school.getId())) {
            throw new IllegalArgumentException("이미 등록된 학교입니다.");
        }

        SchoolUser link = SchoolUser.builder()
                .user(teacher).school(school)
                .position(Position.TEACHER)
                .employmentStatus(EmploymentStatus.HIRED)
                .hireDate(req.getHireDate())
                .build();
        schoolUsers.save(link);

        return toSchoolSimple(link);
    }

    @Override
    public void leaveStaffStore(Long staffId, Long schoolId) {
        SchoolUser su = ensureMapping(staffId, schoolId);
        if (su.getPosition() != Position.TEACHER) {
            throw new IllegalArgumentException("교사 권한이 필요한 요청입니다.");
        }
        schoolUsers.delete(su);

        User u = users.findById(staffId).orElseThrow();
        if (schoolId.equals(u.getActiveSchoolId())) {
            Long nextActive = schoolUsers.findByUser_Id(staffId).stream()
                    .findFirst()
                    .map(s -> s.getSchool().getId())
                    .orElse(null);
            u.setActiveSchoolId(nextActive);
            users.save(u);
            evictRoleCache(staffId);
        }
    }
}
