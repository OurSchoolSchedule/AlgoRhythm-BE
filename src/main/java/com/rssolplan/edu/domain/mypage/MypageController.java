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

    @GetMapping("/owner/profile")
    public ResponseEntity<OwnerProfileResponse> getOwnerProfile(@AuthenticationPrincipal Long ownerId) {
        return ResponseEntity.ok(service.getOwnerProfile(ownerId));
    }

    @PutMapping("/owner/profile")
    public ResponseEntity<OwnerProfileResponse> updateOwnerProfile(
            @AuthenticationPrincipal Long ownerId,
            @RequestBody OwnerProfileUpdateRequest request) {
        return ResponseEntity.ok(service.updateOwnerProfile(ownerId, request));
    }

    @GetMapping("/owner/school")
    public ResponseEntity<OwnerSchoolResponse> getOwnerActiveSchool(@AuthenticationPrincipal Long ownerId) {
        return ResponseEntity.ok(service.getOwnerActiveSchool(ownerId));
    }

    @PutMapping("/owner/school")
    public ResponseEntity<OwnerSchoolResponse> updateOwnerActiveSchool(
            @AuthenticationPrincipal Long ownerId,
            @RequestBody OwnerSchoolUpdateRequest request) {
        return ResponseEntity.ok(service.updateOwnerActiveSchool(ownerId, request));
    }

    @GetMapping("/owner/schools")
    public ResponseEntity<List<SchoolSimpleResponse>> listOwnerSchools(@AuthenticationPrincipal Long ownerId) {
        return ResponseEntity.ok(service.listOwnerSchools(ownerId));
    }

    @PostMapping("/owner/schools")
    public ResponseEntity<SchoolSimpleResponse> addOwnerSchool(
            @AuthenticationPrincipal Long ownerId,
            @RequestBody OwnerCreateSchoolRequest request) {
        return ResponseEntity.status(201).body(service.addOwnerSchool(ownerId, request));
    }

    @DeleteMapping("/owner/schools/{schoolId}")
    public ResponseEntity<Void> removeOwnerSchool(
            @AuthenticationPrincipal Long ownerId,
            @PathVariable Long schoolId) {
        service.removeOwnerSchool(ownerId, schoolId);
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
