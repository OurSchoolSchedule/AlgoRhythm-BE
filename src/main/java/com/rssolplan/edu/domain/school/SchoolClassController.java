package com.rssolplan.edu.domain.school;

import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/school/classes")
@RequiredArgsConstructor
public class SchoolClassController {

    private final SchoolClassRepository schoolClassRepository;
    private final SchoolRepository schoolRepository;
    private final SchoolUserRepository schoolUserRepository;
    private final AuthorizationService authService;

    @GetMapping
    public ResponseEntity<List<SchoolClassResponse>> getClasses(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Integer academicYear) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        List<SchoolClass> classes = academicYear != null
                ? schoolClassRepository.findBySchool_IdAndAcademicYear(schoolId, academicYear)
                : schoolClassRepository.findBySchool_Id(schoolId);
        return ResponseEntity.ok(classes.stream().map(SchoolClassResponse::new).collect(Collectors.toList()));
    }

    @OwnerOnly
    @PostMapping
    public ResponseEntity<SchoolClassResponse> createClass(
            @AuthenticationPrincipal Long userId,
            @RequestBody SchoolClassRequest req) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));

        if (schoolClassRepository.findBySchool_IdAndAcademicYearAndGradeAndClassNumber(
                schoolId, req.getAcademicYear(), req.getGrade(), req.getClassNumber()).isPresent()) {
            throw new IllegalStateException("이미 동일한 학년도/학년/반이 존재합니다.");
        }

        SchoolUser homeroomTeacher = null;
        if (req.getHomeroomTeacherSchoolUserId() != null) {
            homeroomTeacher = schoolUserRepository.findById(req.getHomeroomTeacherSchoolUserId())
                    .orElseThrow(() -> new NotFoundException("담임 교사를 찾을 수 없습니다."));
            if (!homeroomTeacher.getSchool().getId().equals(schoolId)) {
                throw new ForbiddenException("해당 학교 소속 교사가 아닙니다.");
            }
        }

        SchoolClass schoolClass = SchoolClass.builder()
                .school(school)
                .academicYear(req.getAcademicYear())
                .grade(req.getGrade())
                .classNumber(req.getClassNumber())
                .homeroomTeacher(homeroomTeacher)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SchoolClassResponse(schoolClassRepository.save(schoolClass)));
    }

    @OwnerOnly
    @PutMapping("/{classId}")
    public ResponseEntity<SchoolClassResponse> updateClass(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long classId,
            @RequestBody SchoolClassRequest req) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolClass schoolClass = schoolClassRepository.findById(classId)
                .orElseThrow(() -> new NotFoundException("학급을 찾을 수 없습니다."));
        if (!schoolClass.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 학급이 아닙니다.");
        }

        SchoolUser homeroomTeacher = null;
        if (req.getHomeroomTeacherSchoolUserId() != null) {
            homeroomTeacher = schoolUserRepository.findById(req.getHomeroomTeacherSchoolUserId())
                    .orElseThrow(() -> new NotFoundException("담임 교사를 찾을 수 없습니다."));
            if (!homeroomTeacher.getSchool().getId().equals(schoolId)) {
                throw new ForbiddenException("해당 학교 소속 교사가 아닙니다.");
            }
        }

        schoolClass.setGrade(req.getGrade());
        schoolClass.setClassNumber(req.getClassNumber());
        schoolClass.setHomeroomTeacher(homeroomTeacher);

        return ResponseEntity.ok(new SchoolClassResponse(schoolClassRepository.save(schoolClass)));
    }

    @OwnerOnly
    @DeleteMapping("/{classId}")
    public ResponseEntity<Void> deleteClass(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long classId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolClass schoolClass = schoolClassRepository.findById(classId)
                .orElseThrow(() -> new NotFoundException("학급을 찾을 수 없습니다."));
        if (!schoolClass.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 학급이 아닙니다.");
        }
        schoolClassRepository.delete(schoolClass);
        return ResponseEntity.noContent().build();
    }

    @Getter @Setter
    public static class SchoolClassRequest {
        private int academicYear;
        private int grade;
        private int classNumber;
        private Long homeroomTeacherSchoolUserId;
    }

    @Getter
    public static class SchoolClassResponse {
        private final Long id;
        private final int academicYear;
        private final int grade;
        private final int classNumber;
        private final Long homeroomTeacherSchoolUserId;
        private final String homeroomTeacherName;

        public SchoolClassResponse(SchoolClass c) {
            this.id = c.getId();
            this.academicYear = c.getAcademicYear();
            this.grade = c.getGrade();
            this.classNumber = c.getClassNumber();
            this.homeroomTeacherSchoolUserId = c.getHomeroomTeacher() != null ? c.getHomeroomTeacher().getId() : null;
            this.homeroomTeacherName = c.getHomeroomTeacher() != null ? c.getHomeroomTeacher().getUser().getUsername() : null;
        }
    }
}
