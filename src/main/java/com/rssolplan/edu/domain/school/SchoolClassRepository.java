package com.rssolplan.edu.domain.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchoolClassRepository extends JpaRepository<SchoolClass, Long> {

    List<SchoolClass> findBySchool_Id(Long schoolId);

    List<SchoolClass> findBySchool_IdAndAcademicYear(Long schoolId, int academicYear);

    Optional<SchoolClass> findBySchool_IdAndAcademicYearAndGradeAndClassNumber(
            Long schoolId, int academicYear, int grade, int classNumber);
}
