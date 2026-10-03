package com.rssolplan.edu.domain.schedule.attendance;

import com.rssolplan.edu.domain.school.SchoolUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 동시 첫 출근 요청 시 unique 제약 충돌을 안전하게 처리하기 위해
 * REQUIRES_NEW 트랜잭션으로 분리된 헬퍼.
 * 부모 트랜잭션과 별도의 세션에서 실행되므로 충돌 예외를 여기서 처리할 수 있다.
 */
@Component
@RequiredArgsConstructor
class TeacherAttendanceCreator {

    private final TeacherAttendanceRepository attendanceRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TeacherAttendance getOrCreate(SchoolUser schoolUser, LocalDate today) {
        try {
            return attendanceRepository.findBySchoolUser_IdAndWorkDate(schoolUser.getId(), today)
                    .orElseGet(() -> attendanceRepository.saveAndFlush(
                            TeacherAttendance.builder()
                                    .schoolUser(schoolUser)
                                    .workDate(today)
                                    .status(TeacherAttendanceStatus.BEFORE_WORK)
                                    .build()));
        } catch (DataIntegrityViolationException ex) {
            // 동시 요청으로 먼저 생성된 레코드를 반환한다.
            return attendanceRepository.findBySchoolUser_IdAndWorkDate(schoolUser.getId(), today)
                    .orElseThrow(() -> new IllegalStateException("출근 기록 생성 중 오류가 발생했습니다."));
        }
    }
}
