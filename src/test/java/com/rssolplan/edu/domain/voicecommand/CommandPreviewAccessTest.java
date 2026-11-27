package com.rssolplan.edu.domain.voicecommand;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.generation.TimetableRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.Timetable;
import com.rssolplan.edu.domain.schedule.operations.LegacyApprovalGuard;
import com.rssolplan.edu.domain.schedule.operations.ScheduleAccess;
import com.rssolplan.edu.domain.schedule.substitute.SubstituteRequestRepository;
import com.rssolplan.edu.domain.schedule.substitute.SubstituteResponseRepository;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteRequest;
import com.rssolplan.edu.domain.schedule.substitute.entity.SubstituteResponse;
import com.rssolplan.edu.domain.school.*;
import com.rssolplan.edu.domain.school.setting.PeriodSetting;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;
import com.rssolplan.edu.global.exception.ForbiddenException;
import com.rssolplan.edu.global.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandPreviewAccessTest {
    private final ScheduleAccess access = mock(ScheduleAccess.class);
    private final SubstituteRequestRepository requests = mock(SubstituteRequestRepository.class);
    private final TimetableRepository timetables = mock(TimetableRepository.class);
    private final SubstituteResponseRepository responses = mock(SubstituteResponseRepository.class);
    private final CommandPreviewService previews = new CommandPreviewService(timetables, requests, responses,
            mock(SchoolUserRepository.class), access, mock(LegacyApprovalGuard.class));
    private final School school = School.builder().id(1L).build();
    private final School foreignSchool = School.builder().id(2L).build();
    private final SchoolUser member = SchoolUser.builder().id(80L).school(school).build();

    @Test
    void teacherCanPreviewOnlyTheirInvitationInTheActiveSchool() {
        when(access.member(10L, false)).thenReturn(member);
        var lesson = Timetable.builder().dayOfWeek(DayOfWeek.MON)
                .schoolClass(SchoolClass.builder().grade(1).classNumber(2).build())
                .subject(Subject.builder().name("과학").build())
                .periodSetting(PeriodSetting.builder().periodNumber(1).build())
                .teacher(SchoolUser.builder().user(User.builder().username("박교사").build()).build()).build();
        var request = SubstituteRequest.builder().id(50L).timetable(lesson).school(school)
                .receiverUserIds("18, 80").substituteDate(LocalDate.of(2026, 11, 25)).build();
        when(requests.findById(50L)).thenReturn(Optional.of(request));
        var intent = new ParsedIntent("SUBSTITUTE_RESPOND", "HIGH", null, null, null, 50L, "ACCEPT", null, null, null, null);

        assertThat(previews.buildPreview(10L, intent)).containsEntry("requestId", 50L);
        request.setReceiverUserIds("8, 180");
        assertThatThrownBy(() -> previews.buildPreview(10L, intent)).isInstanceOf(ForbiddenException.class);
        request.setReceiverUserIds("80");
        request.setSchool(foreignSchool);
        assertThatThrownBy(() -> previews.buildPreview(10L, intent)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void administrationPreviewsRequireAnAdministratorAndASameSchoolRecord() {
        when(access.member(10L, true)).thenThrow(new ForbiddenException("관리자 권한이 필요합니다."));
        when(access.member(11L, true)).thenReturn(member);
        var create = new ParsedIntent("SUBSTITUTE_CREATE", "HIGH", 30L, "2026-11-25", null, null, null, null, null, null, null);
        when(timetables.findById(30L)).thenReturn(Optional.of(Timetable.builder().school(foreignSchool).build()));
        assertThatThrownBy(() -> previews.buildPreview(10L, create)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> previews.buildPreview(11L, create)).isInstanceOf(NotFoundException.class);

        var approve = new ParsedIntent("SUBSTITUTE_APPROVE", "HIGH", null, null, null, null, "APPROVE", 60L, null, null, null);
        var request = SubstituteRequest.builder().school(foreignSchool).build();
        when(responses.findById(60L)).thenReturn(Optional.of(SubstituteResponse.builder().substituteRequest(request).build()));
        assertThatThrownBy(() -> previews.buildPreview(10L, approve)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> previews.buildPreview(11L, approve)).isInstanceOf(NotFoundException.class);
    }
}
