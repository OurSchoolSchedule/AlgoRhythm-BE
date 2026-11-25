package com.rssolplan.edu.domain.school;

import com.rssolplan.edu.domain.roster.TeacherRosterService;
import com.rssolplan.edu.domain.schedule.generation.TimetableRepository;
import com.rssolplan.edu.domain.schedule.generation.TimetableSetRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableSet;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.global.security.AuthorizationService;
import com.rssolplan.edu.global.security.annotation.OwnerOnly;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/school")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolRepository schoolRepository;
    private final SchoolUserRepository schoolUserRepository;
    private final SchoolClassRepository schoolClassRepository;
    private final TimetableRepository timetableRepository;
    private final AuthorizationService authService;
    private final TeacherRosterService roster;
    private final TimetableSetRepository terms;

    /** 현재 활성 학교 요약 조회 */
    @GetMapping("/me")
    public ResponseEntity<SchoolSummaryResponse> getActiveSchool(
            @AuthenticationPrincipal Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));
        List<SchoolUser> members = schoolUserRepository.findBySchool_Id(schoolId);
        return ResponseEntity.ok(new SchoolSummaryResponse(school, members));
    }

    /** 교사 목록 조회 (담당 과목, 담임 학급, 시수 포함) */
    @GetMapping("/teachers")
    public ResponseEntity<List<TeacherSummaryResponse>> getTeachers(
            @AuthenticationPrincipal Long userId, @RequestParam(required = false) Long termId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        List<SchoolUser> teachers = schoolUserRepository.findBySchool_IdAndPosition(
                schoolId, SchoolUser.Position.TEACHER);
        return ResponseEntity.ok(summaries(schoolId, termId, teachers));
    }

    @OwnerOnly
    @GetMapping("/members")
    public ResponseEntity<List<TeacherSummaryResponse>> getMembers(
            @AuthenticationPrincipal Long userId, @RequestParam(required = false) Long termId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        return ResponseEntity.ok(summaries(schoolId, termId, schoolUserRepository.findBySchool_Id(schoolId)));
    }

    private List<TeacherSummaryResponse> summaries(Long schoolId, Long termId, List<SchoolUser> members) {
        TimetableSet term = termId == null ? null : terms.findByIdAndSchool_Id(termId, schoolId)
                .orElseThrow(() -> new NotFoundException("해당 학교의 학기를 찾을 수 없습니다."));
        List<Timetable> lessons = term == null ? timetableRepository.findBySchool_Id(schoolId)
                : timetableRepository.findBySchool_IdAndAcademicYearAndSemester(
                        schoolId, term.getAcademicYear(), term.getSemester());
        List<SchoolClass> classes = term == null ? schoolClassRepository.findBySchool_Id(schoolId)
                : schoolClassRepository.findBySchool_IdAndAcademicYear(schoolId, term.getAcademicYear());
        var lessonsByTeacher = lessons.stream().collect(Collectors.groupingBy(t -> t.getTeacher().getId()));
        var classesByTeacher = classes.stream().filter(c -> c.getHomeroomTeacher() != null)
                .collect(Collectors.groupingBy(c -> c.getHomeroomTeacher().getId()));
        var entries = roster.linkedByMembership(schoolId, termId);
        return members.stream().map(member -> new TeacherSummaryResponse(member,
                lessonsByTeacher.getOrDefault(member.getId(), List.of()),
                classesByTeacher.getOrDefault(member.getId(), List.of()),
                entries.get(member.getId()))).toList();
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
        private final String displayName;
        private final List<TeacherRosterService.SubjectItem> rosterSubjects;
        private final boolean rosterLinked;
        private final Long rosterAssignmentTermId;
        private final TeacherRosterService.Homeroom rosterHomeroomClass;
        private final String position;
        private final String employmentStatus;
        private final List<SubjectInfo> subjects;
        private final List<HomeroomClassInfo> homeroomClasses;
        private final int weeklyLessonCount;

        public TeacherSummaryResponse(SchoolUser su,
                                      List<Timetable> timetables,
                                      List<SchoolClass> homeroomClasses,
                                      TeacherRosterService.Entry entry) {
            this.schoolUserId = su.getId();
            this.userId = su.getUser().getId();
            this.username = su.getUser().getUsername();
            this.displayName = entry == null ? username : entry.displayName();
            var rosterTerm = entry == null ? null : entry.term();
            this.rosterLinked = entry != null;
            this.rosterAssignmentTermId = rosterTerm != null && rosterTerm.assignmentRecorded()
                    ? rosterTerm.termId() : null;
            this.rosterSubjects = rosterTerm == null ? List.of() : rosterTerm.subjects();
            this.rosterHomeroomClass = rosterTerm == null ? null : rosterTerm.homeroomClass();
            this.position = su.getPosition().name();
            this.employmentStatus = su.getEmploymentStatus().name();
            this.subjects = timetables.stream()
                    .map(t -> new SubjectInfo(t.getSubject().getId(), t.getSubject().getName()))
                    .distinct()
                    .collect(Collectors.toList());
            this.homeroomClasses = homeroomClasses.stream()
                    .map(c -> new HomeroomClassInfo(c.getId(), c.getAcademicYear(), c.getGrade(), c.getClassNumber()))
                    .collect(Collectors.toList());
            this.weeklyLessonCount = timetables.size();
        }
    }

    @Getter
    @AllArgsConstructor
    public static class SubjectInfo {
        private final Long subjectId;
        private final String subjectName;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubjectInfo s)) return false;
            return subjectId != null && subjectId.equals(s.subjectId);
        }

        @Override
        public int hashCode() {
            return subjectId != null ? subjectId.hashCode() : 0;
        }
    }

    @Getter
    @AllArgsConstructor
    public static class HomeroomClassInfo {
        private final Long classId;
        private final int academicYear;
        private final int grade;
        private final int classNumber;
    }
}
