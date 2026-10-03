package com.rssolplan.edu.domain.school;

import com.rssolplan.edu.global.security.AuthorizationService;
import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/school")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolRepository schoolRepository;
    private final SchoolUserRepository schoolUserRepository;
    private final AuthorizationService authService;

    /** 현재 활성 학교 요약 조회 */
    @GetMapping("/me")
    public ResponseEntity<SchoolSummaryResponse> getActiveSchool(
            @AuthenticationPrincipal Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new com.rssolplan.edu.global.exception.NotFoundException("학교를 찾을 수 없습니다."));
        List<SchoolUser> members = schoolUserRepository.findBySchool_Id(schoolId);
        return ResponseEntity.ok(new SchoolSummaryResponse(school, members));
    }

    /** 교사 목록 조회 */
    @GetMapping("/teachers")
    public ResponseEntity<List<TeacherSummaryResponse>> getTeachers(
            @AuthenticationPrincipal Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        List<SchoolUser> teachers = schoolUserRepository.findBySchool_IdAndPosition(
                schoolId, SchoolUser.Position.TEACHER);
        List<TeacherSummaryResponse> responses = teachers.stream()
                .map(TeacherSummaryResponse::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /** 전체 구성원 목록 조회 (ADMIN용) */
    @OwnerOnly
    @GetMapping("/members")
    public ResponseEntity<List<TeacherSummaryResponse>> getMembers(
            @AuthenticationPrincipal Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        List<SchoolUser> members = schoolUserRepository.findBySchool_Id(schoolId);
        List<TeacherSummaryResponse> responses = members.stream()
                .map(TeacherSummaryResponse::new)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @Getter
    @AllArgsConstructor
    public static class SchoolSummaryResponse {
        private Long schoolId;
        private String schoolCode;
        private String name;
        private String address;
        private int totalMemberCount;
        private int teacherCount;
        private int adminCount;

        public SchoolSummaryResponse(School school, List<SchoolUser> members) {
            this.schoolId = school.getId();
            this.schoolCode = school.getSchoolCode();
            this.name = school.getName();
            this.address = school.getAddress();
            this.totalMemberCount = members.size();
            this.teacherCount = (int) members.stream()
                    .filter(m -> m.getPosition() == SchoolUser.Position.TEACHER).count();
            this.adminCount = (int) members.stream()
                    .filter(m -> m.getPosition() == SchoolUser.Position.ADMIN).count();
        }
    }

    @Getter
    public static class TeacherSummaryResponse {
        private final Long schoolUserId;
        private final Long userId;
        private final String username;
        private final String position;
        private final String employmentStatus;

        public TeacherSummaryResponse(SchoolUser su) {
            this.schoolUserId = su.getId();
            this.userId = su.getUser().getId();
            this.username = su.getUser().getUsername();
            this.position = su.getPosition().name();
            this.employmentStatus = su.getEmploymentStatus().name();
        }
    }
}
