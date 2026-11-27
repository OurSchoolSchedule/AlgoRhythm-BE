package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;
import static com.rssolplan.edu.domain.schedule.operations.ChangeService.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rssolplan.edu.domain.schedule.DayOfWeek;
import com.rssolplan.edu.domain.schedule.workshifts.TimetableService;
import com.rssolplan.edu.domain.schedule.workshifts.dto.TimetableDto;
import com.rssolplan.edu.domain.schedule.loadimport.*;
import com.rssolplan.edu.domain.schedule.operations.*;
import com.rssolplan.edu.domain.schedule.operations.DatedLessons.Lesson;
import com.rssolplan.edu.global.exception.NotFoundException;
import com.rssolplan.edu.global.exception.ForbiddenException;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Use a disposable MySQL 8 database. Each fixture owns its school, members and term. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "M3_MYSQL_TEST", matches = "1")
class MilestoneThreeMySqlTest {
    @Autowired DataSource source;
    @Autowired JdbcTemplate db;
    @Autowired GenerationJobs jobs;
    @Autowired GenerationStore store;
    @Autowired PublicationService publication;
    @Autowired DatedLessons lessons;
    @Autowired TimetableService timetables;
    @Autowired ChangeService changes;
    @Autowired CandidateInputService inputs;
    @MockitoBean LoadPreviewStore previews;
    final LocalDate monday = LocalDate.of(2026, 11, 23);
    long school = ThreadLocalRandom.current().nextLong(100000000L, 8000000000L), term, admin, other;
    Request request;

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(
                        new ClassPathResource(
                                "db/migration/V20261122_01__school_load_and_schedule_versions.sql"),
                        new ClassPathResource(
                                "db/migration/V20261124_01__generation_and_lesson_operations.sql"),
                        new ClassPathResource("db/migration/V20261125_11__school_teacher_roster.sql"),
                        new ClassPathResource("db/migration/V20261126_31__school_roster_names.sql"))
                .execute(source);
        db.update("insert into school(id,name) values(?,'fixture')", school);
        for (int i = 0; i < 7; i++) {
            db.update(
                    "insert into users(id,active_school_id,username) values(?,?,?)",
                    school + i,
                    school,
                    "member" + i);
            db.update(
                    "insert into school_user(id,school_id,user_id,position,employment_status)"
                        + " values(?,?,?,?,'HIRED')",
                    school + i,
                    school,
                    school + i,
                    i < 2 ? "ADMIN" : "TEACHER");
        }
        admin = school;
        other = school + 1;
        db.update("insert into subject(id,school_id,name) values(?,?,'수학')", school, school);
        db.update(
                "insert into school_class(id,school_id,academic_year,class_number,grade)"
                    + " values(?,?,2026,1,1)",
                school,
                school);
        db.update(
                "insert into school_setting(id,school_id,break_duration,period_duration)"
                    + " values(?,?,10,50)",
                school,
                school);
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            db.update(
                    "insert into"
                        + " period_setting(id,school_setting_id,period_number,start_time,end_time)"
                        + " values(?,?,?,?,?)",
                    school + i,
                    school,
                    i + 1,
                    LocalTime.of(9 + i, 0),
                    LocalTime.of(9 + i, 50));
            slots.add(new Slot(DayOfWeek.MON, i + 1));
        }
        term =
                publication.term(
                        admin, new PublicationService.NewTerm(2026, 2, monday, monday.plusDays(8)));
        var id = UUID.randomUUID();
        request = new Request(id, slots, null);
        var demands = new ArrayList<LoadWorkbookParser.Demand>();
        for (int i = 2; i <= 4; i++)
            demands.add(
                    new LoadWorkbookParser.Demand(
                            "" + (school + i), "" + school, "" + school, 1, "C" + i));
        when(previews.read(any(), any(), eq(id)))
                .thenReturn(new LoadWorkbookParser.Result("수학", 3, 3, demands, id));
    }

    private Response generate() {
        return jobs.sync(admin, term, null, request);
    }

    private PublicationService.Publish choose(Response r) {
        return new PublicationService.Publish(
                r.jobId(), r.candidates().get(0).candidateId(), monday, r.expectedPublishRev());
    }

    private List<Lesson> publish() {
        publication.publish(admin, term, choose(generate()));
        return lessons.date(admin, term, monday);
    }

    @Test
    void termListIncludesOnlyTheActiveSchoolsTermsInDescendingStartOrder() {
        var earlier = new PublicationService.NewTerm(2026, 1, monday.minusMonths(8), monday.minusMonths(4));
        long first = publication.term(admin, earlier);
        long emptySchool = school + 100;
        db.update("insert into school(id,name) values(?,'empty school')", emptySchool);
        db.update("insert into school_user(school_id,user_id,position,employment_status) values(?,?,'TEACHER','HIRED')",
                emptySchool, school + 2);

        var expected = List.of(
                new PublicationService.TermSummary(term, 2026, 2, monday, monday.plusDays(8)),
                new PublicationService.TermSummary(first, 2026, 1, earlier.startDate(), earlier.endDate()));
        assertThat(publication.terms(admin)).isEqualTo(expected);
        assertThat(publication.terms(school + 2)).isEqualTo(expected);
        db.update("update users set active_school_id=? where id=?", emptySchool, school + 2);
        assertThat(publication.terms(school + 2)).isEmpty();
        assertThat(publication.terms(admin)).isEqualTo(expected);
    }

    @Test
    void weeklyReadPreservesLegacyUntilEffectiveAndResolvesEachDayOfARevisedWeek() {
        long teacher = school + 2;
        for (String day : List.of("MON", "WED", "FRI")) {
            db.update("""
                    insert into timetable(school_id,academic_year,semester,school_class_id,
                        period_setting_id,day_of_week,subject_id,teacher_id) values(?,2026,2,?,?,?,?,?)
                    """, school, school, school, day, school, teacher);
        }
        assertThat(timetables.week(teacher, true, 2026, 2, null, monday))
                .hasSize(3).allMatch(r -> r.getId() != null && r.getWeeklyLessonId() == null);

        // First publication takes effect on Wednesday. Tuesday's new slot is still hidden.
        long first = version(1, monday.plusDays(2));
        weeklySlot(first, 2, teacher);
        weeklySlot(first, 3, teacher);
        weeklySlot(first, 5, teacher);
        long second = version(2, monday.plusDays(4));
        weeklySlot(second, 5, school + 3);

        var rows = timetables.week(admin, false, 2026, 2, term, monday.plusDays(5));
        assertThat(rows).extracting(TimetableDto::getDayOfWeek)
                .containsExactly(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI);
        assertThat(rows.get(0).getId()).isNotNull();
        assertThat(rows.subList(1, 3)).allMatch(r -> r.getId() == null && r.getWeeklyLessonId() != null);
        assertThat(rows.get(2).getTeacherId()).isEqualTo(school + 3);
        assertThat(timetables.week(teacher, true, 2026, 2, term, monday))
                .extracting(TimetableDto::getDayOfWeek).containsExactly(DayOfWeek.MON, DayOfWeek.WED);
        assertThat(timetables.week(school + 4, true, 2026, 2, term, monday)).isEmpty();
        assertThat(timetables.week(admin, false, null, null, null, monday.minusWeeks(1))).isEmpty();

        // A newer revision can be backdated. Its empty Friday supersedes both older revisions.
        long third = version(3, monday.plusDays(3));
        weeklySlot(third, 4, teacher);
        assertThat(timetables.week(admin, false, 2026, 2, null, monday))
                .extracting(TimetableDto::getDayOfWeek)
                .containsExactly(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.THU);
        assertThat(timetables.week(admin, false, 2026, 2, term, monday.plusWeeks(1))).isEmpty();
    }

    @Test
    void explicitWeeklyTermMustMatchBothTheActiveSchoolAndTheRequestedSemester() {
        assertThatThrownBy(() -> timetables.week(admin, false, 2026, 1, term, monday))
                .isInstanceOf(NotFoundException.class);
        long anotherSchool = school + 100;
        db.update("insert into school(id,name) values(?,'other school')", anotherSchool);
        db.update("""
                insert into timetable_set(school_id,academic_year,semester,start_date,end_date)
                values(?,2026,2,?,?)
                """, anotherSchool, monday, monday.plusMonths(1));
        long foreignTerm = db.queryForObject("select id from timetable_set where school_id=?", Long.class, anotherSchool);
        assertThatThrownBy(() -> timetables.week(admin, false, 2026, 2, foreignTerm, monday))
                .isInstanceOf(NotFoundException.class);
    }

    private long version(int revision, LocalDate from) {
        db.update("insert into schedule_version(term_id,version_no,effective_from) values(?,?,?)",
                term, revision, from);
        return db.queryForObject("select id from schedule_version where term_id=? and version_no=?",
                Long.class, term, revision);
    }

    private void weeklySlot(long version, int day, long teacher) {
        db.update("""
                insert into weekly_lesson(version_id,lesson_key,class_id,subject_id,teacher_id,weekday,period_id)
                values(?,?,?,?,?,?,?)
                """, version, "day-" + day, school, school, teacher, day, school);
    }

    @Test
    void pollingAndPublicationReplayAndStaleCompetingSelection() throws Exception {
        request = new Request(request.validatedLoadId(), request.teachingSlots(), null, Objective.MIN_TEACHER_GAPS);
        var ticket = jobs.submit(admin, term, request);
        GenerationStore.Poll poll = null;
        for (int i = 0; i < 120; i++) {
            jobs.pump();
            poll = store.poll(admin, term, ticket.jobId());
            if (poll.state().equals("READY")) break;
            Thread.sleep(80);
        }
        assertThat(poll.state()).isEqualTo("READY");
        assertThat(poll.objective()).isEqualTo(Objective.MIN_TEACHER_GAPS);
        assertThat(poll.teachingSlots()).isEqualTo(request.teachingSlots());
        assertThat(poll.result().candidates()).allSatisfy(c -> assertThat(c.score().teacherGapPeriods()).isZero());
        var first = choose(poll.result());
        var competing = choose(generate());
        var outcomes =
                race(
                        () -> publication.publish(admin, term, first),
                        () -> publication.publish(other, term, competing));
        assertThat(outcomes).filteredOn(PublicationService.Published.class::isInstance).hasSize(1);
        assertThat(outcomes)
                .filteredOn(ScheduleConflict.class::isInstance)
                .singleElement()
                .satisfies(
                        x ->
                                assertThat(((ScheduleConflict) x).code)
                                        .isEqualTo("STALE_PUBLICATION"));
        var winner = outcomes.get(0) instanceof PublicationService.Published ? first : competing;
        assertThat(publication.publish(admin, term, winner).weeklyLessons()).isEqualTo(3);
        verify(previews, times(2)).read(any(), any(), any());
        assertThat(lessons.date(admin, term, monday)).hasSize(3);
        assertThat(lessons.date(admin, term, monday.plusWeeks(1))).hasSize(3);
        assertThat(
                        db.queryForObject(
                                "select count(*) from schedule_version where term_id=?",
                                Long.class,
                                term))
                .isEqualTo(1);
    }

    @Test
    void wrongCandidateAndChangedHardConstraintCannotPublish() {
        var result = generate();
        assertThatThrownBy(
                        () ->
                                publication.publish(
                                        admin,
                                        term,
                                        new PublicationService.Publish(
                                                result.jobId(), "0".repeat(64), monday, 0L)))
                .isInstanceOf(NotFoundException.class);
        db.update(
                "insert into teacher_availability(school_user_id,day_of_week,period_number)"
                    + " values(?,'MON',1)",
                school + 2);
        assertThatThrownBy(() -> publication.publish(admin, term, choose(result)))
                .isInstanceOf(ScheduleConflict.class);
        assertThat(lessons.date(admin, term, monday)).isEmpty();
    }

    @Test
    void fencedWorkerCannotFinishAfterReclaim() {
        var snap = inputs.freeze(admin, term, null, request);
        var old = UUID.randomUUID();
        var next = UUID.randomUUID();
        var ticket = store.insert(snap, old, 60000000L);
        db.update(
                "update generation_job set lease_until=timestampadd(second,-2,utc_timestamp())"
                    + " where id=?",
                ticket.jobId().toString());
        store.renew(ticket.jobId(), old);
        assertThat(store.finish(ticket.jobId(), old, "FAILED", null)).isFalse();
        assertThat(store.claim(ticket.jobId(), next)).isTrue();
        assertThat(store.finish(ticket.jobId(), old, "FAILED", null)).isFalse();
        assertThat(store.finish(ticket.jobId(), next, "FAILED", null)).isTrue();
    }

    private Change cover(Lesson l, long teacher) {
        var c =
                changes.create(
                        admin,
                        term,
                        new NewChange(
                                ChangeService.Kind.SUBSTITUTE,
                                l.id(),
                                l.rev(),
                                null,
                                null,
                                teacher));
        return changes.respond(teacher, term, c.id(), true, c.rev());
    }

    private Change swap(Lesson l, Lesson r) {
        var c =
                changes.create(
                        l.actual().teacherId(),
                        term,
                        new NewChange(
                                ChangeService.Kind.SWAP, l.id(), l.rev(), r.id(), r.rev(), null));
        return changes.respond(r.actual().teacherId(), term, c.id(), true, c.rev());
    }

    private Outcome approve(long user, Change c, UUID key) {
        return changes.approve(
                user, term, c.id(), new Approval(c.rev(), c.firstRev(), c.secondRev()), key);
    }

    @Test
    void coverAgainstCover() {
        conflict("CC");
    }

    @Test
    void coverAgainstSwap() {
        conflict("CS");
    }

    @Test
    void cancellationBlocksRequestsAndAcceptanceWithoutChangingTheRequest() {
        var week = publish();
        var a = week.get(0);
        var b = week.get(1);
        var body = new NewChange(ChangeService.Kind.SWAP, a.id(), a.rev(), b.id(), b.rev(), null);
        var c = changes.create(a.actual().teacherId(), term, body);
        db.update("update dated_lesson set actual=json_set(actual,'$.cancelled',true),rev=rev+1 where id=?", b.id());

        assertThat(lessons.get(term, b.id()).actual().cancelled()).isTrue();
        assertThatThrownBy(() -> changes.create(a.actual().teacherId(), term, body))
                .isInstanceOfSatisfying(ScheduleConflict.class, x -> assertThat(x.code).isEqualTo("LESSON_CANCELLED"));
        assertThatThrownBy(() -> changes.respond(b.actual().teacherId(), term, c.id(), true, 0))
                .isInstanceOfSatisfying(ScheduleConflict.class, x -> assertThat(x.code).isEqualTo("LESSON_CANCELLED"));
        assertThat(changes.list(admin, term)).singleElement().satisfies(row -> {
            assertThat(row.state()).isEqualTo("PENDING");
            assertThat(row.rev()).isZero();
        });
    }

    @Test
    void teachingAnAssignedLessonDoesNotGiveAnAdministratorSwapPermission() {
        var week = publish();
        var a = week.get(0);
        var b = week.get(1);
        db.update("update dated_lesson set actual=json_set(actual,'$.teacherId',?) where id=?", admin, a.id());
        var body = new NewChange(ChangeService.Kind.SWAP, a.id(), a.rev(), b.id(), b.rev(), null);
        assertThatThrownBy(() -> changes.create(admin, term, body)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> changes.create(other, term,
                new NewChange(ChangeService.Kind.SUBSTITUTE, b.id(), b.rev(), null, null, admin)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void twoSwapsSharingALesson() {
        conflict("SS");
    }

    private void conflict(String kind) {
        var week = publish();
        var a = week.get(0);
        var b = week.get(1);
        var c = week.get(2);
        var left = kind.startsWith("C") ? cover(a, school + 5) : swap(a, b);
        var right = kind.endsWith("C") ? cover(a, school + 6) : swap(a, c);
        var keys = List.of(UUID.randomUUID(), UUID.randomUUID());
        var results =
                race(
                        () -> approve(admin, left, keys.get(0)),
                        () -> approve(other, right, keys.get(1)));
        assertThat(results).filteredOn(Outcome.class::isInstance).hasSize(1);
        assertThat(results)
                .filteredOn(ScheduleConflict.class::isInstance)
                .singleElement()
                .satisfies(x -> assertThat(((ScheduleConflict) x).code).isEqualTo("STALE_LESSON"));
        boolean leftWon = results.get(0) instanceof Outcome;
        int index = leftWon ? 0 : 1;
        assertThat(approve(leftWon ? admin : other, leftWon ? left : right, keys.get(index)))
                .isEqualTo(results.get(index));
        assertThat(
                        db.queryForObject(
                                "select count(*) from lesson_approval where school_id=?",
                                Long.class,
                                school))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "select count(*) from notifications where school_id=? and type like"
                                    + " '%APPROVED%'",
                                Long.class, school))
                .isEqualTo(2);
        assertThat(changes.list(admin, term))
                .extracting(Change::state)
                .containsExactlyInAnyOrder("ACCEPTED", "APPROVED");
        assertThat(lessons.date(admin, term, monday.plusWeeks(1))).allMatch(l -> l.rev() == 0);
        assertThat(lessons.get(term, a.id()).rev()).isEqualTo(1);
        if (leftWon && kind.equals("SS")) assertThat(lessons.get(term, c.id()).rev()).isZero();
    }

    @Autowired org.springframework.web.context.WebApplicationContext web;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Test
    void frontEndCanFollowLocationAndCommitExactlyTheReturnedCandidate() throws Exception {
        var api = MockMvcBuilders.webAppContextSetup(web).build();
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, List.of()));
        try {
            String base = "/api/v2/terms/" + term;
            var accepted =
                    api.perform(
                                    post(base + "/generations")
                                            .contentType("application/json")
                                            .content(mapper.writeValueAsBytes(request)))
                            .andExpect(status().isAccepted())
                            .andExpect(header().string("Retry-After", "2"))
                            .andReturn()
                            .getResponse();
            String location = accepted.getHeader("Location");
            UUID id =
                    UUID.fromString(
                            mapper.readTree(accepted.getContentAsByteArray())
                                    .get("jobId")
                                    .asText());
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!store.poll(admin, term, id).state().equals("READY")
                    && System.nanoTime() < deadline) {
                jobs.pump();
                Thread.sleep(40);
            }
            var poll =
                    api.perform(get(location))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.state").value("READY"))
                            .andExpect(jsonPath("$.result.candidates.length()").value(4))
                            .andReturn()
                            .getResponse();
            String chosen =
                    mapper.readTree(poll.getContentAsByteArray())
                            .at("/result/candidates/1/candidateId")
                            .asText();
            var data =
                    mapper.writeValueAsBytes(
                            new PublicationService.Publish(id, chosen, monday, 0L));
            for (int attempt = 0; attempt < 2; attempt++)
                api.perform(
                                post(base + "/publications")
                                        .contentType("application/json")
                                        .content(data))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.publishRev").value(1));
            api.perform(get(base + "/lessons").param("date", monday.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3));
            api.perform(
                            post(base + "/lesson-changes/1/approval")
                                    .contentType("application/json")
                                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Autowired com.rssolplan.edu.domain.schedule.substitute.SubstituteService legacy;

    @Test
    void legacyCoverAndPublishedSwapShareOneRevision() {
        var week = publish();
        var a = week.get(0);
        var b = week.get(1);
        db.update(
                "insert into"
                    + " timetable(id,school_id,academic_year,semester,school_class_id,period_setting_id,day_of_week,subject_id,teacher_id)"
                    + " values(?,?,2026,2,?,?,'MON',?,?)",
                school,
                school,
                a.classId(),
                a.actual().periodId(),
                a.subjectId(),
                a.actual().teacherId());
        var created =
                legacy.create(
                        admin,
                        new com.rssolplan.edu.domain.schedule.substitute.dto
                                .SubstituteCreateRequest(school, monday, "fixture"));
        var accepted =
                legacy.respond(
                        school + 5,
                        created.id(),
                        new com.rssolplan.edu.domain.schedule.substitute.dto
                                .SubstituteRespondRequest("ACCEPT"));
        var swap = swap(a, b);
        var results =
                race(
                        () ->
                                legacy.approve(
                                        admin,
                                        accepted.responseId(),
                                        new com.rssolplan.edu.domain.schedule.substitute.dto
                                                .SubstituteApprovalRequest("APPROVE"),
                                        UUID.randomUUID()),
                        () -> approve(other, swap, UUID.randomUUID()));
        assertThat(results).filteredOn(ScheduleConflict.class::isInstance).hasSize(1);
        assertThat(lessons.get(term, a.id()).rev()).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "select count(*) from lesson_approval where school_id=?",
                                Long.class,
                                school))
                .isEqualTo(1);
    }

    @Test
    void schoolSetupCandidateCanBePublishedInMatchingTerm() {
        var r = jobs.sync(admin, null, 2026, request);
        assertThat(publication.publish(admin, term, choose(r)).weeklyLessons()).isEqualTo(3);
    }

    @Test
    void pollingScopeAndActiveSchoolGuard() {
        var snap = inputs.freeze(admin, term, null, request);
        var t = store.insert(snap, UUID.randomUUID(), 60000000L);
        assertThatThrownBy(() -> jobs.submit(admin, term, request))
                .isInstanceOfSatisfying(
                        ScheduleConflict.class,
                        c -> assertThat(c.activeJobId).isEqualTo(t.jobId().toString()));
        long otherTerm =
                publication.term(
                        admin,
                        new PublicationService.NewTerm(
                                2027, 1, monday.plusYears(1), monday.plusYears(1).plusDays(8)));
        assertThatThrownBy(() -> store.poll(admin, otherTerm, t.jobId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void swapThenCoverPreservesMovedSubjectAndFollowingWeek() {
        var week = publish();
        var l = week.get(0);
        var r = week.get(1);
        approve(admin, swap(l, r), UUID.randomUUID());
        var moved = lessons.get(term, l.id());
        assertThat(moved.actual().periodId()).isEqualTo(r.actual().periodId());
        approve(admin, cover(moved, school + 5), UUID.randomUUID());
        var actual = lessons.get(term, l.id());
        assertThat(actual.planned().teacherId()).isEqualTo(l.actual().teacherId());
        assertThat(actual.actual().teacherId()).isEqualTo(school + 5);
        assertThat(actual.rev()).isEqualTo(2);
        assertThat(lessons.get(term, r.id()).rev()).isEqualTo(1);
        assertThat(lessons.date(admin, term, monday.plusWeeks(1))).allMatch(x -> x.rev() == 0);
    }

    private static List<Object> race(Supplier<?> a, Supplier<?> b) {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CyclicBarrier(2);
        try {
            List<Future<Object>> tasks = new ArrayList<>();
            for (var call : List.of(a, b))
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await(5, TimeUnit.SECONDS);
                                    try {
                                        return call.get();
                                    } catch (ScheduleConflict conflict) {
                                        return conflict;
                                    }
                                }));
            return List.of(
                    tasks.get(0).get(10, TimeUnit.SECONDS), tasks.get(1).get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
