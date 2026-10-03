package com.rssolplan.edu.domain.schedule.workshifts;

import com.rssolplan.edu.domain.schedule.generation.TimetableRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.schedule.workshifts.dto.TimetableCreateDto;
import com.rssolplan.edu.domain.schedule.workshifts.dto.TimetableDto;
import com.rssolplan.edu.domain.school.SchoolClass;
import com.rssolplan.edu.domain.school.SchoolClassRepository;
import com.rssolplan.edu.domain.school.SchoolRepository;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.school.Subject;
import com.rssolplan.edu.domain.school.SubjectRepository;
import com.rssolplan.edu.domain.school.setting.PeriodSetting;
import com.rssolplan.edu.domain.school.setting.SchoolSettingRepository;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TimetableService {

    private final TimetableRepository timetableRepository;
    private final SchoolRepository schoolRepository;
    private final SchoolUserRepository schoolUserRepository;
    private final SchoolClassRepository schoolClassRepository;
    private final SubjectRepository subjectRepository;
    private final SchoolSettingRepository schoolSettingRepository;
    private final AuthorizationService authService;

    @Transactional(readOnly = true)
    public List<TimetableDto> getTimetables(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        return timetableRepository.findBySchool_Id(schoolId)
                .stream().map(TimetableDto::new).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TimetableDto> getTimetablesByYearSemester(Long userId, int academicYear, int semester) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        return timetableRepository.findBySchool_IdAndAcademicYearAndSemester(schoolId, academicYear, semester)
                .stream().map(TimetableDto::new).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TimetableDto> getMyTimetable(Long userId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolUser me = authService.getSchoolUserOrThrow(userId, schoolId);
        return timetableRepository.findByTeacher_Id(me.getId())
                .stream().map(TimetableDto::new).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TimetableDto> getMyTimetableByYearSemester(Long userId, int academicYear, int semester) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);
        SchoolUser me = authService.getSchoolUserOrThrow(userId, schoolId);
        return timetableRepository.findByTeacher_IdAndAcademicYearAndSemester(me.getId(), academicYear, semester)
                .stream().map(TimetableDto::new).collect(Collectors.toList());
    }

    @Transactional
    public TimetableDto createTimetable(Long userId, TimetableCreateDto dto) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);

        var school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NotFoundException("학교를 찾을 수 없습니다."));
        var schoolClass = schoolClassRepository.findById(dto.getSchoolClassId())
                .orElseThrow(() -> new NotFoundException("학급을 찾을 수 없습니다."));
        if (!schoolClass.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 학급이 아닙니다.");
        }
        var subject = subjectRepository.findById(dto.getSubjectId())
                .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다."));
        if (!subject.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 과목이 아닙니다.");
        }
        var teacher = schoolUserRepository.findById(dto.getTeacherSchoolUserId())
                .orElseThrow(() -> new NotFoundException("교사를 찾을 수 없습니다."));
        if (!teacher.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교 소속 교사가 아닙니다.");
        }

        var schoolSetting = schoolSettingRepository.findBySchool_Id(schoolId)
                .orElseThrow(() -> new NotFoundException("학교 설정이 존재하지 않습니다."));

        PeriodSetting periodSetting = schoolSetting.getPeriods().stream()
                .filter(p -> p.getId().equals(dto.getPeriodSettingId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("교시 설정을 찾을 수 없습니다."));

        if (timetableRepository.existsBySchoolClass_IdAndAcademicYearAndSemesterAndDayOfWeekAndPeriodSetting_Id(
                dto.getSchoolClassId(), dto.getAcademicYear(), dto.getSemester(),
                dto.getDayOfWeek(), dto.getPeriodSettingId())) {
            throw new IllegalStateException("해당 학급의 해당 교시에 이미 시간표가 존재합니다.");
        }
        if (timetableRepository.existsByTeacher_IdAndAcademicYearAndSemesterAndDayOfWeekAndPeriodSetting_Id(
                dto.getTeacherSchoolUserId(), dto.getAcademicYear(), dto.getSemester(),
                dto.getDayOfWeek(), dto.getPeriodSettingId())) {
            throw new IllegalStateException("해당 교사는 이미 해당 교시에 다른 수업이 있습니다.");
        }

        Timetable timetable = Timetable.builder()
                .school(school)
                .academicYear(dto.getAcademicYear())
                .semester(dto.getSemester())
                .schoolClass(schoolClass)
                .periodSetting(periodSetting)
                .dayOfWeek(dto.getDayOfWeek())
                .subject(subject)
                .teacher(teacher)
                .build();

        return new TimetableDto(timetableRepository.save(timetable));
    }

    @Transactional
    public TimetableDto updateTimetable(Long userId, Long timetableId, TimetableCreateDto dto) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);

        Timetable timetable = timetableRepository.findById(timetableId)
                .orElseThrow(() -> new NotFoundException("시간표를 찾을 수 없습니다."));

        if (!timetable.getSchool().getId().equals(schoolId)) {
            throw new SecurityException("해당 학교의 시간표가 아닙니다.");
        }

        var subject = subjectRepository.findById(dto.getSubjectId())
                .orElseThrow(() -> new NotFoundException("과목을 찾을 수 없습니다."));
        if (!subject.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교의 과목이 아닙니다.");
        }
        var teacher = schoolUserRepository.findById(dto.getTeacherSchoolUserId())
                .orElseThrow(() -> new NotFoundException("교사를 찾을 수 없습니다."));
        if (!teacher.getSchool().getId().equals(schoolId)) {
            throw new ForbiddenException("해당 학교 소속 교사가 아닙니다.");
        }

        timetable.setSubject(subject);
        timetable.setTeacher(teacher);

        return new TimetableDto(timetableRepository.save(timetable));
    }

    @Transactional
    public void deleteTimetable(Long userId, Long timetableId) {
        Long schoolId = authService.getActiveSchoolIdOrThrow(userId);

        Timetable timetable = timetableRepository.findById(timetableId)
                .orElseThrow(() -> new NotFoundException("시간표를 찾을 수 없습니다."));

        if (!timetable.getSchool().getId().equals(schoolId)) {
            throw new SecurityException("해당 학교의 시간표가 아닙니다.");
        }

        timetableRepository.delete(timetable);
    }
}
