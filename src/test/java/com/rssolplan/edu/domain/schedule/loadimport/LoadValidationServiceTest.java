package com.rssolplan.edu.domain.schedule.loadimport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rssolplan.edu.domain.schedule.generation.TimetableSetRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableSet;
import com.rssolplan.edu.domain.school.*;
import com.rssolplan.edu.global.exception.*;
import com.rssolplan.edu.global.security.AuthorizationService;

import org.junit.jupiter.api.Test;

import java.util.*;

class LoadValidationServiceTest {
    final AuthorizationService auth = mock(AuthorizationService.class);
    final TimetableSetRepository terms = mock(TimetableSetRepository.class);
    final SchoolUserRepository members = mock(SchoolUserRepository.class);
    final SubjectRepository subjects = mock(SubjectRepository.class);
    final SchoolClassRepository classes = mock(SchoolClassRepository.class);
    final LoadValidationService service =
            new LoadValidationService(auth, terms, members, subjects, classes);

    private void member(SchoolUser.Position role) {
        when(auth.getActiveSchoolIdOrThrow(42L)).thenReturn(1L);
        when(auth.getSchoolUserOrThrow(42L, 1L))
                .thenReturn(
                        SchoolUser.builder()
                                .position(role)
                                .employmentStatus(SchoolUser.EmploymentStatus.HIRED)
                                .build());
    }

    @Test
    void teacherCannotValidateSchoolUpload() {
        member(SchoolUser.Position.TEACHER);
        assertThatThrownBy(() -> service.catalog(42L, null, 2026, Map.of()))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(terms, members, subjects, classes);
    }

    @Test
    void otherSchoolsTermIsNotDisclosed() {
        member(SchoolUser.Position.ADMIN);
        when(terms.findById(7L))
                .thenReturn(
                        Optional.of(
                                TimetableSet.builder()
                                        .school(School.builder().id(9L).build())
                                        .build()));
        assertThatThrownBy(() -> service.catalog(42L, 7L, null, Map.of()))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(members, subjects, classes);
    }

    @Test
    void newSchoolUsesYearScopedCatalogWithoutCreatingTerm() {
        member(SchoolUser.Position.ADMIN);
        service.catalog(42L, null, 2026, Map.of());
        verify(classes).findBySchool_IdAndAcademicYear(1L, 2026);
        verifyNoInteractions(terms);
    }
}
