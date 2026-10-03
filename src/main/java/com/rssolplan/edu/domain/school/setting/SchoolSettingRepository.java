package com.rssolplan.edu.domain.school.setting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SchoolSettingRepository extends JpaRepository<SchoolSetting, Long> {

    Optional<SchoolSetting> findBySchool_Id(Long schoolId);
}
