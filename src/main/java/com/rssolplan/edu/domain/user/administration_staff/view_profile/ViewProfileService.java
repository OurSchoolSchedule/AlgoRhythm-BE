package com.rssolplan.edu.domain.user.administration_staff.view_profile;

import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class ViewProfileService {

    private final SchoolUserRepository schoolUserRepository;

    /**
     * Returns a school member's profile after checking the caller's ADMIN membership in
     * that school. Days worked is the calendar-day difference from hire date to today,
     * or zero when no hire date is stored; a future hire date yields a negative value.
     *
     * @param schoolUserId target membership ID, rather than user ID
     * @throws IllegalArgumentException if the membership is missing or access is denied
     */
    @Transactional(readOnly = true)
    public ViewProfileResponse getEmployeeProfile(Long adminId, Long schoolUserId) {

        SchoolUser schoolUser = schoolUserRepository.findById(schoolUserId)
                .orElseThrow(() -> new IllegalArgumentException("SCHOOL_USER_NOT_FOUND"));

        boolean isAdmin = schoolUserRepository
                .findByUser_IdAndSchool_Id(adminId, schoolUser.getSchool().getId())
                .stream()
                .anyMatch(su -> su.getPosition() == SchoolUser.Position.ADMIN);

        if (!isAdmin) {
            throw new IllegalArgumentException("ACCESS_DENIED");
        }

        var user = schoolUser.getUser();
        var school = schoolUser.getSchool();

        LocalDate hireDate = schoolUser.getHireDate();
        long daysWorked = hireDate != null ? ChronoUnit.DAYS.between(hireDate, LocalDate.now()) : 0;

        return new ViewProfileResponse(
                user.getUsername(),
                user.getProfileImageUrl(),
                schoolUser.getEmploymentStatus().name(),
                schoolUser.getPosition().name(),
                school.getName(),
                user.getEmail(),
                hireDate,
                daysWorked
        );
    }
}
