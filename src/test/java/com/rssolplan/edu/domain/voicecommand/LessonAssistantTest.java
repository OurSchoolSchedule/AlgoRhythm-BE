package com.rssolplan.edu.domain.voicecommand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.schedule.operations.ScheduleAccess;
import com.rssolplan.edu.domain.schedule.generation.TimetableSetRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableSet;
import com.rssolplan.edu.domain.voicecommand.dto.*;
import com.rssolplan.edu.global.exception.*;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.global.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LessonAssistantTest {
    private final OpenAiProperties config = new OpenAiProperties();
    private final ScheduleAccess access = mock(ScheduleAccess.class);
    private final CommandDraftStore drafts = mock(CommandDraftStore.class);
    private final CommandExecutorService writer = mock(CommandExecutorService.class);
    private final TimetableSetRepository terms = mock(TimetableSetRepository.class);
    private final CommandPreviewService preview = new CommandPreviewService(null, null, null, null, access, null, terms);

    @Test
    void recognizedOfflinePhrasesCanOnlyOpenReadOnlyPickers() {
        var date = LocalDate.of(2026, 11, 27);
        var cover = LessonPhrases.tryRead("수요일 3교시 수업을 대신할 수 있는 공강 교사를 찾아줘", date);
        assertThat(cover.substituteDate()).isEqualTo("2026-11-25");
        assertThat(cover.periodNumber()).isEqualTo(3);
        assertThat(cover.intentType()).isEqualTo("COVER_OPTIONS");
        assertThat(LessonPhrases.tryRead("내가 요청한 보결의 처리 상태를 알려줘", date).intentType()).isEqualTo("CHANGE_STATUS");
        assertThat(LessonPhrases.tryRead("강태영 선생님과 교환 가능한 수업을 찾아줘", date).teacherName()).isEqualTo("강태영");
        assertThat(LessonPhrases.tryRead("이번 주 내 시간표를 알려줘", date).intentType()).isEqualTo("MY_WEEK");
        assertThat(LessonPhrases.tryRead("보결을 수락하고 최종 승인해줘", date)).isNull();
    }

    @Test
    void modelInterpretsAnUnusualPhraseButCannotSupplyAnUnmentionedDateOrCreateADraft() throws Exception {
        var builder = RestClient.builder();
        var model = MockRestServiceServer.bindTo(builder).build();
        var json = new ObjectMapper();
        var arguments = json.writeValueAsString(Map.of("intentType", "SWAP_OPTIONS", "confidence", "HIGH",
                "substituteDate", "2026-11-27", "periodNumber", 6, "teacherName", "강태영"));
        var response = Map.of("choices", java.util.List.of(Map.of("message", Map.of("tool_calls",
                java.util.List.of(Map.of("function", Map.of("arguments", arguments)))))));
        model.expect(r -> assertThat(r.getURI().getHost()).isEqualTo("api.openai.com"))
                .andRespond(withSuccess(json.writeValueAsString(response), MediaType.APPLICATION_JSON));
        config.setApiKey("test-fixture");
        var parser = new IntentParserService(config, json, builder.build());
        var service = new VoiceCommandService(parser, preview, writer, drafts);
        var teacher = SchoolUser.builder().school(School.builder().id(5L).build())
                .position(SchoolUser.Position.TEACHER).build();
        when(access.member(7L, false)).thenReturn(teacher);
        var result = service.preview(7L, new VoiceCommandRequest("강태영 선생님과 6교시 수업을 맞바꿀 만할까?", "ko"));
        assertThat(result.preview()).isEqualTo(Map.of("periodNumber", 6,
                "teacherName", "강태영", "schoolId", 5L, "userId", 7L));
        assertThat(result.draftToken()).isNull();
        model.verify();
        verifyNoInteractions(writer, drafts);
    }

    @Test
    void pendingUserCannotPreviewOrCreateAnyDraft() {
        var parser = mock(IntentParserService.class);
        var service = new VoiceCommandService(parser, preview, writer, drafts);
        when(access.member(4L, false)).thenThrow(new ForbiddenException("승인 대기"));
        assertThatThrownBy(() -> service.preview(4L, new VoiceCommandRequest("내 시간표를 알려줘", "ko")))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(parser, writer, drafts);
    }

    @Test
    void calendarDatesAndWeekRangesRetainBothExchangeRoles() {
        var sunday = LocalDate.of(2027, 1, 3);
        var swap = LessonPhrases.tryRead("상대 수업은 내일이고 내 수업은 오늘이야. 교환 요청", sunday);
        assertThat(swap.substituteDate()).isEqualTo("2027-01-03");
        assertThat(swap.targetDate()).isEqualTo("2027-01-04");
        assertThat(LessonPhrases.tryRead("상대 수업은 내일이야. 교환 요청", sunday).substituteDate()).isNull();
        var targetOnly = LessonPhrases.tryRead("내 수업과 다음 주 목요일 상대 수업 교환 요청", sunday);
        assertThat(targetOnly.substituteDate()).isNull();
        assertThat(targetOnly.targetDate()).isEqualTo("2027-01-07");
        var weeks = LessonPhrases.tryRead("다음 주 내 수업 보결 요청", sunday);
        assertThat(weeks.substituteDate()).isNull();
        assertThat(weeks.weekStart()).isEqualTo("2027-01-04");
        var week = LessonPhrases.tryRead("다음 주 내 시간표", sunday);
        assertThat(week.substituteDate()).isEqualTo("2027-01-04");
        assertThat(LessonDates.isWeekQuery("다음 주 내 시간표", week)).isTrue();
        var day = LessonPhrases.tryRead("다음 주 월요일 내 시간표", sunday);
        assertThat(LessonDates.isWeekQuery("다음 주 월요일 내 시간표", day)).isFalse();
        var dates = LessonPhrases.tryRead("2026년 12월 21일 내 수업을 23일 상대 수업과 교환 요청", sunday);
        assertThat(dates.substituteDate()).isEqualTo("2026-12-21");
        assertThat(dates.targetDate()).isEqualTo("2026-12-23");
        assertThatThrownBy(() -> LessonPhrases.tryRead("2026-02-30 내 시간표", sunday))
                .isInstanceOf(IntentParseException.class);
        assertThatThrownBy(() -> LessonPhrases.tryRead("오늘 내일 모레 교환 요청", sunday))
                .isInstanceOf(IntentParseException.class);
    }

    @Test
    void sourceAndTargetRemainIndependentAndCannotChangeExistingDatesOrTheTerm() {
        var teacher = SchoolUser.builder().school(School.builder().id(5L).build()).position(SchoolUser.Position.TEACHER).build();
        when(access.member(7, false)).thenReturn(teacher);
        var term = TimetableSet.builder().id(88L).startDate(LocalDate.of(2026, 9, 1)).endDate(LocalDate.of(2026, 12, 15))
                .excludedDates(Set.of(LocalDate.of(2026, 12, 3))).build();
        when(terms.findByIdAndSchool_Id(88L, 5L)).thenReturn(Optional.of(term));
        var service = new VoiceCommandService(new IntentParserService(config, null, null), preview, writer, drafts);
        var req = new VoiceCommandRequest("2026-12-03 내 수업과 2026-12-20 상대 수업 교환 신청", "ko",
                5L, 88L, "2026-12-02", "2026-12-14");
        var result = service.preview(7L, req);
        assertThat(result.preview()).containsEntry("date", "2026-12-03").containsEntry("targetDate", "2026-12-20")
                .containsEntry("dateFromText", true).containsEntry("targetDateFromText", true)
                .containsEntry("dateConflict", true).containsEntry("targetDateConflict", true)
                .containsEntry("dateStatus","EXCLUDED_DATE").containsEntry("targetDateStatus", "OUTSIDE_TERM")
                .containsEntry("termId", 88L);
        assertThat(result.draftToken()).isNull();
        var selected = service.preview(7L, new VoiceCommandRequest("내 시간표", "ko", 5L, 88L, "2026-09-10", null)).preview();
        assertThat(selected).containsEntry("date","2026-09-10").doesNotContainKeys("dateConflict", "dateFromText");
        var guidance = service.preview(7L, new VoiceCommandRequest("보결은 어떻게 신청하나요?", "ko"));
        assertThat(guidance.intentType()).isEqualTo("GUIDE");
        assertThat(guidance.preview()).doesNotContainKeys("date","targetDate");
        assertThat(guidance.draftToken()).isNull();
        assertThatThrownBy(() -> service.preview(7L, new VoiceCommandRequest("내 시간표", null, 6L, 88L, null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.preview(7L, new VoiceCommandRequest("내 시간표", null, 5L, 777L, null, null)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(writer, drafts);
    }

    @Test
    void confirmationRechecksScopeEvenForAnAvailabilityChange() {
        var slots = java.util.List.of(new UnavailabilitySlot("MON", 1, null));
        var intent = new ParsedIntent("AVAILABILITY_ADD","HIGH",null,null,null,null,null,null,slots,null,null);
        var draft = new DraftData(7L,intent,5L,88L,null,null);
        when(drafts.getAndDelete("fixture")).thenReturn(draft);
        var service = new VoiceCommandService(null,preview,writer,drafts);
        assertThatThrownBy(() -> service.confirm(7L, new CommandConfirmRequest("fixture",5L,89L,null,null)))
                .isInstanceOf(DraftExpiredException.class);
        when(access.member(7L,false)).thenReturn(SchoolUser.builder().school(School.builder().id(6L).build()).build());
        assertThatThrownBy(() -> service.confirm(7L,new CommandConfirmRequest("fixture",5L,88L,null,null)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(writer);
    }
}
