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
@RequestMapping("/api/school/subjects")
@RequiredArgsConstructor
public class SubjectController {

    private final SubjectRepository subjectRepository;
    private final SchoolRepository schoolRepository;
    private final AuthorizationService authService;

    @GetMapping
    public ResponseEntity<List<SubjectResponse>> getSubjects(
            @AuthenticationPrincipal Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        List<SubjectResponse> responses = subjectRepository.findBySchool_Id(schoolId)
                .stream().map(SubjectResponse::new).collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    @OwnerOnly
    @PostMapping
    public ResponseEntity<SubjectResponse> createSubject(
            @AuthenticationPrincipal Long userId,
            @RequestBody SubjectRequest req) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));
        Subject subject = Subject.builder()
                .school(school)
                .name(req.getName())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(new SubjectResponse(subjectRepository.save(subject)));
    }

    @OwnerOnly
    @PutMapping("/{subjectId}")
    public ResponseEntity<SubjectResponse> updateSubject(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long subjectId,
            @RequestBody SubjectRequest req) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다."));
        if (!subject.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 과목이 아닙니다.");
        }
        subject.setName(req.getName());
        return ResponseEntity.ok(new SubjectResponse(subjectRepository.save(subject)));
    }

    @OwnerOnly
    @DeleteMapping("/{subjectId}")
    public ResponseEntity<Void> deleteSubject(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long subjectId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        Subject subject = subjectRepository.findById(subjectId)
                .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다."));
        if (!subject.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 과목이 아닙니다.");
        }
        subjectRepository.delete(subject);
        return ResponseEntity.noContent().build();
    }

    @Getter @Setter
    public static class SubjectRequest {
        private String name;
    }

    @Getter
    public static class SubjectResponse {
        private final Long id;
        private final String name;

        public SubjectResponse(Subject s) {
            this.id = s.getId();
            this.name = s.getName();
        }
    }
}
