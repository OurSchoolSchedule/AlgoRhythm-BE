package com.rssolplan.edu.domain.mypage;

import com.rssolplan.edu.domain.mypage.dto.*;

import java.util.List;

public interface MypageService {

    // 활성 학교 선택
    ActiveSchoolResponse getActiveSchool(Long userId);
    ActiveSchoolResponse updateActiveSchool(Long userId, Long schoolId);

    // 교감/교장(Admin) 관련
    AdminProfileResponse getAdminProfile(Long adminId);
    AdminProfileResponse updateAdminProfile(Long adminId, AdminProfileUpdateRequest request);

    AdminSchoolResponse getAdminActiveSchool(Long adminId);
    AdminSchoolResponse updateAdminActiveSchool(Long adminId, AdminSchoolUpdateRequest request);

    List<SchoolSimpleResponse> listAdminSchools(Long adminId);
    SchoolSimpleResponse addAdminSchool(Long adminId, AdminCreateSchoolRequest request);
    void removeAdminSchool(Long adminId, Long schoolId);

    // 교사(Teacher) 관련
    TeacherProfileResponse getTeacherProfile(Long teacherId);
    TeacherProfileResponse updateTeacherProfile(Long teacherId, TeacherProfileUpdateRequest request);

    List<SchoolSimpleResponse> listTeacherSchools(Long teacherId);
    SchoolSimpleResponse joinTeacherSchool(Long teacherId, TeacherJoinSchoolRequest request);
    void leaveTeacherSchool(Long teacherId, Long schoolId);
}
