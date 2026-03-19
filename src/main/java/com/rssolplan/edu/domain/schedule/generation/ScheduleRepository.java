package com.rssolplan.edu.domain.schedule.generation;

import com.rssolplan.edu.domain.schedule.generation.entity.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleRepository extends JpaRepository<Schedule, Integer> {
}
