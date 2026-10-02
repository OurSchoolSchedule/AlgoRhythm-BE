package com.rssolplan.edu.domain.school;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubjectRepository extends JpaRepository<Subject, Long> {

    List<Subject> findBySchool_Id(Long schoolId);
}
