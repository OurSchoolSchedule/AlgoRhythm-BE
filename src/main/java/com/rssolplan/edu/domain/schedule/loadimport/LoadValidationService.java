package com.rssolplan.edu.domain.schedule.loadimport;

import com.rssolplan.edu.domain.roster.RosterMatchingService;
import com.rssolplan.edu.domain.schedule.generation.TimetableSetRepository;
import com.rssolplan.edu.domain.school.*;
import com.rssolplan.edu.global.exception.*;
import com.rssolplan.edu.global.security.AuthorizationService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LoadValidationService {
    private final AuthorizationService auth;
    private final TimetableSetRepository terms;
    private final SchoolUserRepository members;
    private final SubjectRepository subjects;
    private final SchoolClassRepository classes;
    private final RosterMatchingService matches;

    @Transactional(readOnly = true)
    public LoadCatalog catalog(Long userId, Long termId, Integer year, Map<String, Long> aliases) {
        Long schoolId = auth.getActiveSchoolIdOrThrow(userId);
        SchoolUser member = auth.getSchoolUserOrThrow(userId, schoolId);
        if (member.getPosition() != SchoolUser.Position.ADMIN
                || member.getEmploymentStatus() != SchoolUser.EmploymentStatus.HIRED)
            throw new ForbiddenException("재직 중인 학교 관리자만 시수표를 검증할 수 있습니다.");
        if (termId != null) {
            var term =
                    terms.findById(termId)
                            .filter(t -> t.getSchool().getId().equals(schoolId))
                            .orElseThrow(() -> new NotFoundException("학기를 찾을 수 없습니다."));
            year = term.getAcademicYear();
        }
        if (year == null || year < 1900 || year > 9999)
            throw LoadProblem.file(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_YEAR",
                    "학교의 학년도를 지정하세요.");
        Map<Long, Long> teachingIdByLogin = new HashMap<>();
        matches.directoryLinks(schoolId).forEach((teaching, login) -> teachingIdByLogin.put(login, teaching));
        var teachers =
                members
                        .findBySchool_IdAndEmploymentStatus(
                                schoolId, SchoolUser.EmploymentStatus.HIRED)
                        .stream()
                        .filter(m -> !teachingIdByLogin.containsKey(m.getId()))
                        .map(m -> new LoadCatalog.Name(m.getId(), m.getSchoolDisplayName()))
                        .toList();
        var schoolSubjects = subjects.findBySchool_Id(schoolId);
        var names =
                new LoadCatalog.Names(
                        schoolSubjects.stream()
                                .map(s -> new LoadCatalog.Name(s.getId(), s.getName()))
                                .toList());
        names.addAliases(
                aliases, schoolSubjects.stream().map(Subject::getId).collect(Collectors.toSet()));
        var classMap = new HashMap<LoadCatalog.ClassKey, Long>();
        for (SchoolClass sc : classes.findBySchool_IdAndAcademicYear(schoolId, year))
            classMap.put(new LoadCatalog.ClassKey(sc.getGrade(), sc.getClassNumber()), sc.getId());
        return new LoadCatalog(schoolId, year, new LoadCatalog.Names(teachers), names, classMap,
                Map.copyOf(teachingIdByLogin));
    }
}
