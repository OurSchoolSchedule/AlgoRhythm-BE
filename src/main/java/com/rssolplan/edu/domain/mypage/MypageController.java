package com.rssolplan.edu.domain.mypage;

import com.rssolplan.edu.domain.mypage.dto.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/mypage")
public class MypageController {

    private final MypageService service;

    public MypageController(MypageService service) {
        this.service = service;
    }

    // ===== 활성 학교 =====

    @GetMapping("/active-school")
    public ResponseEntity<ActiveSchoolResponse> getActiveSchool(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(service.getActiveSchool(userId));
    }

    @PatchMapping("/active-school/{schoolId}")
    public ResponseEntity<ActiveSchoolResponse> updateActiveSchool(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long schoolId) {
        return ResponseEntity.ok(service.updateActiveSchool(userId, schoolId));
    }

    // ===== 교감/교장(Admin) =====

    @GetMapping("/admin/profile")
    public ResponseEntity<AdminProfileResponse> getAdminProfile(@AuthenticationPrincipal Long adminId) {
        return ResponseEntity.ok(service.getAdminProfile(adminId));
    }

    @PutMapping("/admin/profile")
    public ResponseEntity<AdminProfileResponse> updateAdminProfile(
            @AuthenticationPrincipal Long adminId,
            @RequestBody AdminProfileUpdateRequest request) {
        return ResponseEntity.ok(service.updateAdminProfile(adminId, request));
    }

    @GetMapping("/admin/school")
    public ResponseEntity<AdminSchoolResponse> getAdminActiveSchool(@AuthenticationPrincipal Long adminId) {
        return ResponseEntity.ok(service.getAdminActiveSchool(adminId));
    }

    @PutMapping("/admin/school")
    public ResponseEntity<AdminSchoolResponse> updateAdminActiveSchool(
            @AuthenticationPrincipal Long adminId,
            @RequestBody AdminSchoolUpdateRequest request) {
        return ResponseEntity.ok(service.updateAdminActiveSchool(adminId, request));
    }

    @GetMapping("/admin/schools")
    public ResponseEntity<List<SchoolSimpleResponse>> listAdminSchools(@AuthenticationPrincipal Long adminId) {
        return ResponseEntity.ok(service.listAdminSchools(adminId));
    }

    @PostMapping("/admin/schools")
    public ResponseEntity<SchoolSimpleResponse> addAdminSchool(
            @AuthenticationPrincipal Long adminId,
            @RequestBody AdminCreateSchoolRequest request) {
        return ResponseEntity.status(201).body(service.addAdminSchool(adminId, request));
    }

    @DeleteMapping("/admin/schools/{schoolId}")
    public ResponseEntity<Void> removeAdminSchool(
            @AuthenticationPrincipal Long adminId,
            @PathVariable Long schoolId) {
        service.removeAdminSchool(adminId, schoolId);
        return ResponseEntity.noContent().build();
    }

    // ===== 교사(Teacher) =====

    @GetMapping("/teacher/profile")
    public ResponseEntity<TeacherProfileResponse> getTeacherProfile(@AuthenticationPrincipal Long teacherId) {
        return ResponseEntity.ok(service.getTeacherProfile(teacherId));
    }

    @PutMapping("/teacher/profile")
    public ResponseEntity<TeacherProfileResponse> updateTeacherProfile(
            @AuthenticationPrincipal Long teacherId,
            @RequestBody TeacherProfileUpdateRequest request) {
        return ResponseEntity.ok(service.updateTeacherProfile(teacherId, request));
    }

    @GetMapping("/teacher/schools")
    public ResponseEntity<List<SchoolSimpleResponse>> listTeacherSchools(@AuthenticationPrincipal Long teacherId) {
        return ResponseEntity.ok(service.listTeacherSchools(teacherId));
    }

    @PostMapping("/teacher/schools")
    public ResponseEntity<SchoolSimpleResponse> joinTeacherSchool(
            @AuthenticationPrincipal Long teacherId,
            @RequestBody TeacherJoinSchoolRequest request) {
        return ResponseEntity.status(201).body(service.joinTeacherSchool(teacherId, request));
    }

    @DeleteMapping("/teacher/schools/{schoolId}")
    public ResponseEntity<Void> leaveTeacherSchool(
            @AuthenticationPrincipal Long teacherId,
            @PathVariable Long schoolId) {
        service.leaveTeacherSchool(teacherId, schoolId);
        return ResponseEntity.noContent().build();
    }
}
