package com.rssolplan.edu.domain.schedule.generation;

import com.rssolplan.edu.domain.schedule.generation.entity.TimetableSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimetableSetRepository extends JpaRepository<TimetableSet, Long> {

    List<TimetableSet> findBySchool_Id(Long schoolId);

    Optional<TimetableSet> findBySchool_IdAndAcademicYearAndSemester(Long schoolId, int academicYear, int semester);
}
