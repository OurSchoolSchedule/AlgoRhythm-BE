package com.rssolplan.edu.domain.voicecommand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.schedule.operations.ScheduleAccess;
import com.rssolplan.edu.domain.school.School;
import com.rssolplan.edu.domain.school.SchoolUser;
import com.rssolplan.edu.domain.voicecommand.dto.VoiceCommandRequest;
import com.rssolplan.edu.global.config.OpenAiProperties;
import com.rssolplan.edu.global.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LessonAssistantTest {
    private final OpenAiProperties config = new OpenAiProperties();
    private final ScheduleAccess access = mock(ScheduleAccess.class);
    private final CommandDraftStore drafts = mock(CommandDraftStore.class);
    private final CommandExecutorService writer = mock(CommandExecutorService.class);
    private final CommandPreviewService preview = new CommandPreviewService(null, null, null, null, access);

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
    void modelIntentReturnsAccountAndSchoolBoundPickerWithoutIssuingExecutionToken() throws Exception {
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
        var result = service.preview(7L, new VoiceCommandRequest("강태영 선생님과 교환할 만한 수업이 있을까?", "ko"));
        assertThat(result.preview()).isEqualTo(Map.of("date", "2026-11-27", "periodNumber", 6,
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
}
