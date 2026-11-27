package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.demoreset.DemoRound;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.generation.TimetableSetRepository;
import com.rssolplan.edu.domain.schedule.generation.entity.TimetableSet;
import com.rssolplan.edu.domain.schedule.loadimport.*;
import com.rssolplan.edu.domain.schedule.workavailability.*;
import com.rssolplan.edu.domain.school.*;
import com.rssolplan.edu.domain.school.setting.*;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.global.exception.GlobalExceptionHandler;
import com.rssolplan.edu.global.security.AuthorizationService;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;

/** HTTP to HTTP: only Redis transport, database repositories and membership lookup are replaced. */
class CandidateApiTest {
    final AuthorizationService auth = mock(AuthorizationService.class);
    final TimetableSetRepository terms = mock(TimetableSetRepository.class);
    final SchoolClassRepository classes = mock(SchoolClassRepository.class);
    final SubjectRepository subjects = mock(SubjectRepository.class);
    final SchoolUserRepository members = mock(SchoolUserRepository.class);
    final SchoolSettingRepository settings = mock(SchoolSettingRepository.class);
    final TeacherAvailabilityRepository blocks = mock(TeacherAvailabilityRepository.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final CandidateRunner runner = new CandidateRunner();
    final ObjectMapper json = new ObjectMapper();
    final Map<String, String> cache = new HashMap<>();
    MockMvc api;
    SchoolUser teacher;
    GenerationJobs jobs;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var values = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(
                        call -> {
                            cache.put(call.getArgument(0), call.getArgument(1));
                            return null;
                        })
                .when(values)
                .set(anyString(), anyString(), eq(Duration.ofMinutes(30)));
        when(values.get(anyString())).thenAnswer(call -> cache.get((String) call.getArgument(0)));
        var loads = new LoadValidationService(auth, terms, members, subjects, classes,
                    mock(com.rssolplan.edu.domain.roster.RosterMatchingService.class));
        var round = org.mockito.Mockito.mock(DemoRound.class);
        org.mockito.Mockito.lenient().when(round.prefix()).thenReturn("");
        var previews = new LoadPreviewStore(redis, json, round);
        var inputs = new CandidateInputService(loads, previews, settings, blocks);
        var store = mock(GenerationStore.class);
        when(store.insert(any(), any(), anyLong()))
                .thenReturn(new GenerationStore.Ticket(UUID.randomUUID(), 0));
        when(store.remainingMicros(any())).thenReturn(60000000L);
        when(store.finish(any(), any(), any(), any())).thenReturn(true);
        jobs = new GenerationJobs(store, inputs, runner);
        api =
                MockMvcBuilders.standaloneSetup(
                                new LoadValidationController(loads, previews),
                                new CandidateController(jobs))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(44L, null, List.of()));
        when(auth.getActiveSchoolIdOrThrow(44L)).thenReturn(1L);
        when(auth.getSchoolUserOrThrow(44L, 1L))
                .thenReturn(SchoolUser.builder().position(SchoolUser.Position.ADMIN).build());
        var school = School.builder().id(1L).build();
        for (long id : List.of(7L, 8L))
            when(terms.findById(id))
                    .thenReturn(
                            Optional.of(
                                    TimetableSet.builder()
                                            .id(id)
                                            .school(school)
                                            .academicYear(2026)
                                            .build()));
        teacher = SchoolUser.builder().id(10L).user(User.builder().username("김교사").build()).build();
        when(members.findBySchool_IdAndEmploymentStatus(1L, SchoolUser.EmploymentStatus.HIRED))
                .thenReturn(List.of(teacher));
        when(subjects.findBySchool_Id(1L))
                .thenReturn(List.of(Subject.builder().id(20L).name("수학").build()));
        when(classes.findBySchool_IdAndAcademicYear(1L, 2026))
                .thenReturn(List.of(SchoolClass.builder().id(30L).grade(1).classNumber(1).build()));
        var period =
                PeriodSetting.builder()
                        .id(7L)
                        .periodNumber(1)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(9, 50))
                        .build();
        when(settings.findBySchool_Id(1L))
                .thenReturn(Optional.of(SchoolSetting.builder().periods(List.of(period)).build()));
        when(blocks.findBySchoolUser_School_Id(1L))
                .thenReturn(
                        List.of(
                                TeacherAvailability.builder()
                                        .schoolUser(teacher)
                                        .dayOfWeek(DayOfWeek.MON)
                                        .periodNumber(1)
                                        .build()));
    }

    @AfterEach
    void close() {
        jobs.close();
        runner.close();
        SecurityContextHolder.clearContext();
    }

    private String preview(String path) throws Exception {
        try (var book = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("시수표");
            var h = sheet.createRow(0);
            var row = sheet.createRow(1);
            String[] headings = {"교사", "과목", "1-1", "합계"}, vals = {"김교사", "수학", "2", "2"};
            for (int i = 0; i < headings.length; i++) {
                h.createCell(i).setCellValue(headings[i]);
                row.createCell(i).setCellValue(vals[i]);
            }
            book.write(out);
            String result =
                    api.perform(
                                    multipart(path)
                                            .file(
                                                    new MockMultipartFile(
                                                            "file",
                                                            "시수.xlsx",
                                                            "application/octet-stream",
                                                            out.toByteArray()))
                                            .param("forGeneration", "true")
                                            .param("academicYear", "2026"))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.totalPeriods").value(2))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            return json.readTree(result).required("validatedLoadId").asText();
        }
    }

    private ResultActions generate(String path, String id) throws Exception {
        return api.perform(
                post(path)
                        .contentType("application/json")
                        .content(
                                """
{"validatedLoadId":"%s","preferences":[{"teacherId":"10","kind":"WANT","anyOf":[{"day":"TUE","period":1}]}]}
"""
                                        .formatted(id)));
    }

    @Test
    void termPreviewToFourRankedCandidatesIncludesWishesAndExistingHardBlocks() throws Exception {
        String id = preview("/api/v2/terms/7/load-imports/validate");
        String result =
                generate("/api/v2/terms/7/schedule-candidates", id)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("READY"))
                        .andExpect(jsonPath("$.candidates.length()").value(4))
                        .andExpect(jsonPath("$.candidates[0].score.wantsMet").value(1))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        for (var candidate : json.readTree(result).get("candidates")) {
            assertThat(candidate.get("placements").size()).isEqualTo(2);
            candidate
                    .get("placements")
                    .forEach(a -> assertThat(a.at("/slot/day").asText()).isNotEqualTo("MON"));
        }
        assertThat(cache).hasSize(1);
        verify(terms, never()).save(any());
    }

    @Test
    void schoolYearPreviewWorksBeforeATermExistsAndAtItsSameYearTerm() throws Exception {
        String id = preview("/api/v2/school/load-imports/validate");
        generate("/api/v2/school/schedule-candidates?academicYear=2026", id)
                .andExpect(status().isOk());
        generate("/api/v2/terms/7/schedule-candidates", id).andExpect(status().isOk());
        generate("/api/v2/school/schedule-candidates?academicYear=2025", id)
                .andExpect(status().isNotFound());
    }

    @Test
    void otherTermsExpiredAndOtherSchoolsCannotReadPreview() throws Exception {
        String id = preview("/api/v2/terms/7/load-imports/validate");
        generate("/api/v2/terms/8/schedule-candidates", id).andExpect(status().isNotFound());
        when(auth.getActiveSchoolIdOrThrow(44L)).thenReturn(9L);
        when(auth.getSchoolUserOrThrow(44L, 9L))
                .thenReturn(SchoolUser.builder().position(SchoolUser.Position.ADMIN).build());
        generate("/api/v2/school/schedule-candidates?academicYear=2026", id)
                .andExpect(status().isNotFound());
        when(auth.getActiveSchoolIdOrThrow(44L)).thenReturn(1L);
        cache.clear();
        generate("/api/v2/terms/7/schedule-candidates", id).andExpect(status().isNotFound());
    }

    @Test
    void changedRosterRequiresNewValidationAndTeacherRoleIsForbidden() throws Exception {
        String id = preview("/api/v2/terms/7/load-imports/validate");
        when(subjects.findBySchool_Id(1L)).thenReturn(List.of());
        generate("/api/v2/terms/7/schedule-candidates", id).andExpect(status().isConflict());
        when(auth.getSchoolUserOrThrow(44L, 1L))
                .thenReturn(SchoolUser.builder().position(SchoolUser.Position.TEACHER).build());
        generate("/api/v2/terms/7/schedule-candidates", id).andExpect(status().isForbidden());
    }

    @Test
    void rawDemandJsonCannotReplaceValidatedLoad() throws Exception {
        api.perform(
                        post("/api/v2/terms/7/schedule-candidates")
                                .contentType("application/json")
                                .content("{\"demands\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(redis);
    }

    @Test
    void invalidCalendarAndUnknownPreferenceTeacherAreRejected() throws Exception {
        String id = preview("/api/v2/terms/7/load-imports/validate");
        for (String extra :
                List.of(
                        "\"teachingSlots\":[{\"day\":\"MON\",\"period\":99}]",
                        "\"preferences\":[{\"teacherId\":\"999\",\"kind\":\"WANT\",\"anyOf\":[{\"day\":\"TUE\",\"period\":1}]}]"))
            api.perform(
                            post("/api/v2/terms/7/schedule-candidates")
                                    .contentType("application/json")
                                    .content("{\"validatedLoadId\":\"" + id + "\"," + extra + "}"))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void cacheOutageIsDistinctFromExpiredInput() throws Exception {
        String id = preview("/api/v2/terms/7/load-imports/validate");
        when(redis.opsForValue())
                .thenThrow(
                        new org.springframework.data.redis.RedisConnectionFailureException("test"));
        generate("/api/v2/terms/7/schedule-candidates", id)
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void overlappingBellTimesAreRejectedEvenForDifferentPeriodNumbers() {
        var p1 =
                PeriodSetting.builder()
                        .periodNumber(1)
                        .startTime(LocalTime.of(9, 0))
                        .endTime(LocalTime.of(9, 50))
                        .build();
        var p2 =
                PeriodSetting.builder()
                        .id(7L)
                        .periodNumber(2)
                        .startTime(LocalTime.of(9, 40))
                        .endTime(LocalTime.of(10, 0))
                        .build();
        assertThatThrownBy(() -> CandidateInputService.teachingSlots(List.of(p1, p2), null))
                .isInstanceOf(IllegalStateException.class);
        var disjoint =
                List.of(
                        new CandidateApi.Slot(DayOfWeek.MON, 1),
                        new CandidateApi.Slot(DayOfWeek.TUE, 2));
        assertThat(CandidateInputService.teachingSlots(List.of(p1, p2), disjoint))
                .isEqualTo(disjoint);
    }
}
