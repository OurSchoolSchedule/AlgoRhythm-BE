package com.rssolplan.edu.domain.mypage;

import com.rssolplan.edu.domain.mypage.dto.*;

import java.util.List;

public interface MypageService {

    // 활성 학교 선택
    /**
     * Returns the active school's details and membership status, falling back to the
     * oldest membership when no school is active. The fallback does not change the active school.
     *
     * @throws IllegalArgumentException if the active membership is missing or no school is registered
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    ActiveSchoolResponse getActiveSchool(Long userId);
    /**
     * Selects a school the user belongs to, invalidates the cached role, and returns
     * that school's details and membership status. Redis failures propagate.
     *
     * @throws IllegalArgumentException if the user does not belong to the requested school
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    ActiveSchoolResponse updateActiveSchool(Long userId, Long schoolId);

    // 교감/교장(Admin) 관련
    /**
     * Returns the profile for the active administrator membership, or the oldest
     * membership when no school is active.
     *
     * @throws IllegalArgumentException if the selected membership is missing or has the wrong position
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    AdminProfileResponse getAdminProfile(Long adminId);
    /**
     * Updates non-null username and email fields and returns the administrator profile.
     * Uses the active membership, falling back to the oldest membership.
     *
     * @throws IllegalArgumentException if the selected membership is missing or has the wrong position
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    AdminProfileResponse updateAdminProfile(Long adminId, AdminProfileUpdateRequest request);

    /**
     * Returns the school for the active administrator membership, falling back to the
     * oldest membership when no school is active.
     *
     * @throws IllegalArgumentException if the selected membership is missing or has the wrong position
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    AdminSchoolResponse getAdminActiveSchool(Long adminId);
    /**
     * Updates non-null school name, address, and phone fields and returns the school details.
     * Uses the active administrator membership, falling back to the oldest membership.
     *
     * @throws IllegalArgumentException if the selected membership is missing or has the wrong position
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    AdminSchoolResponse updateAdminActiveSchool(Long adminId, AdminSchoolUpdateRequest request);

    /**
     * Returns administrator memberships ordered by school ID, including any employment status.
     * Returns an empty list when there are no matching memberships.
     */
    List<SchoolSimpleResponse> listAdminSchools(Long adminId);
    /**
     * Creates a school and a hired administrator membership, returning the new membership's
     * school details. If no school is active, activates this school and invalidates the
     * cached role; Redis failures propagate.
     *
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    SchoolSimpleResponse addAdminSchool(Long adminId, AdminCreateSchoolRequest request);
    /**
     * Removes the administrator membership. If its school was active, selects a remaining
     * membership's school or clears the active school, then invalidates the cached role.
     * Redis failures propagate.
     *
     * @throws IllegalArgumentException if the membership is missing or is not an administrator membership
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    void removeAdminSchool(Long adminId, Long schoolId);

    // 교사(Teacher) 관련
    /**
     * Returns the teacher profile and school for the active membership, falling back to
     * the oldest membership when no school is active.
     *
     * @throws IllegalArgumentException if the selected membership is missing or has the wrong position
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    TeacherProfileResponse getTeacherProfile(Long teacherId);
    /**
     * Updates non-null username and email fields and returns the teacher profile.
     * Uses the active membership, falling back to the oldest membership.
     *
     * @throws IllegalArgumentException if the selected membership is missing or has the wrong position
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    TeacherProfileResponse updateTeacherProfile(Long teacherId, TeacherProfileUpdateRequest request);

    /**
     * Returns teacher memberships ordered by school ID, including any employment status.
     * Returns an empty list when there are no matching memberships.
     */
    List<SchoolSimpleResponse> listTeacherSchools(Long teacherId);
    /**
     * Creates a hired teacher membership using the supplied school code and returns its
     * school details. If no school is active, activates this school and invalidates the
     * cached role; Redis failures propagate.
     *
     * @throws IllegalArgumentException if the school code is unknown or the membership already exists
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    SchoolSimpleResponse joinTeacherSchool(Long teacherId, TeacherJoinSchoolRequest request);
    /**
     * Removes the teacher membership. If its school was active, selects a remaining
     * membership's school or clears the active school, then invalidates the cached role.
     * Redis failures propagate.
     *
     * @throws IllegalArgumentException if the membership is missing or is not a teacher membership
     * @throws java.util.NoSuchElementException if the user does not exist
     */
    void leaveTeacherSchool(Long teacherId, Long schoolId);
}
