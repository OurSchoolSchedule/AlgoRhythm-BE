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
import com.rssolplan.edu.domain.schedule.substitute.dto.*;
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
                        new ClassPathResource("db/migration/V20261126_31__school_roster_names.sql"),
                        new ClassPathResource("db/migration/V20261127_41__roster_teaching_link.sql"),
                        new ClassPathResource("db/migration/V20261127_42__lesson_change_notification_target.sql"),
                        new ClassPathResource("db/migration/V20261127_44__public_cover_recipients.sql"),
                        new ClassPathResource("db/migration/V20261127_45__term_excluded_date.sql"),
                        new ClassPathResource("db/migration/V20261127_46__dated_public_cover.sql"))
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
    void calendarEditsValidateAndDetectCompetingAdministratorsWithoutChangingOtherTerms() {
        LocalDate end = monday.plusDays(8);
        var state = new PublicationService.CalendarState(monday, end, List.of());
        var first = new PublicationService.CalendarEdit(monday, end, List.of(monday.plusDays(2), monday), state);
        assertThatThrownBy(() -> publication.editTerm(school + 2, term, first)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> publication.editTerm(admin, term,
                new PublicationService.CalendarEdit(monday, end, List.of(end.plusDays(1)))))
                .isInstanceOf(com.rssolplan.edu.global.exception.BadRequestException.class);
        assertThatThrownBy(() -> publication.editTerm(admin, term,
                new PublicationService.CalendarEdit(monday, end, List.of(monday, monday))))
                .isInstanceOf(com.rssolplan.edu.global.exception.BadRequestException.class);
        var updated = publication.editTerm(admin, term, first);
        assertThat(updated.excludedDates()).containsExactly(monday, monday.plusDays(2));
        assertThat(publication.editTerm(other, term, first)).isEqualTo(updated);
        assertThatThrownBy(() -> publication.editTerm(other, term,
                new PublicationService.CalendarEdit(monday, end, List.of(end), state)))
                .isInstanceOfSatisfying(ScheduleConflict.class, c -> assertThat(c.code).isEqualTo("TERM_CALENDAR_CHANGED"));
        assertThat(publication.terms(school + 2)).containsExactly(updated);
        var another = new PublicationService.NewTerm(2027, 1, end.plusDays(1), end.plusDays(14), List.of(end.plusDays(5)));
        publication.term(admin, another);
        assertThatThrownBy(() -> publication.editTerm(admin, term, new PublicationService.CalendarEdit(monday, end.plusDays(1), List.of())))
                .isInstanceOfSatisfying(ScheduleConflict.class, c -> assertThat(c.code).isEqualTo("TERM_EXISTS"));
    }

    @Test
    void examDatesOmitRegularOccurrencesWhileTheWeeklyTemplateAndProtectedRequestsSurvive() {
        var start = LocalDate.now(ZoneId.of("Asia/Seoul")).plusWeeks(4)
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        var exam = start.plusWeeks(1);
        var end = exam.plusDays(1);
        publication.editTerm(admin, term, new PublicationService.CalendarEdit(start, end, List.of(exam)));
        var result = generate();
        var selected = new PublicationService.Publish(result.jobId(), result.candidates().get(0).candidateId(), start,
                result.expectedPublishRev());
        assertThat(publication.publish(admin, term, selected).weeklyLessons()).isEqualTo(3);
        var first = lessons.date(admin, term, start).get(0);
        assertThat(lessons.date(admin, term, exam)).isEmpty();
        assertThat(timetables.week(admin, false, 2026, 2, term, exam)).isEmpty();
        assertThat(changes.candidates(first.actual().teacherId(), term, first.id(), first.rev(), ChangeService.Kind.SWAP, exam))
                .isEmpty();
        assertThat(db.queryForObject("""
                select count(*) from dated_lesson d join weekly_lesson w on w.id=d.origin_id
                where w.version_id=? and d.origin_date=?
                """, Long.class, first.versionId(), exam)).isZero();
        db.update("""
                insert into timetable(id,school_id,academic_year,semester,school_class_id,period_setting_id,
                        day_of_week,subject_id,teacher_id) values(?,?,2026,2,?,?,'MON',?,?)
                """, school, school, school, first.actual().periodId(), school, first.actual().teacherId());
        assertThatThrownBy(() -> legacy.create(admin, new SubstituteCreateRequest(school, exam, "시험일")))
                .isInstanceOfSatisfying(ScheduleConflict.class, c -> assertThat(c.code).isEqualTo("NON_TEACHING_DATE"));

        publication.editTerm(admin, term, new PublicationService.CalendarEdit(start, end, List.of()));
        var restored = lessons.date(admin, term, exam);
        assertThat(restored).hasSize(3);
        publication.editTerm(admin, term, new PublicationService.CalendarEdit(start, end, List.of(exam)));
        assertThat(lessons.date(admin, term, exam)).isEmpty();
        publication.editTerm(admin, term, new PublicationService.CalendarEdit(start, end.plusWeeks(1), List.of()));
        assertThat(lessons.date(admin, term, exam)).isEqualTo(restored);
        assertThat(lessons.date(admin, term, exam.plusWeeks(1))).hasSize(3);
        var covered = restored.get(0);
        var invite = changes.createBatch(admin, term, new CoverBatch(covered.id(), covered.rev(), List.of(school + 5))).get(0);
        assertThatThrownBy(() -> publication.editTerm(admin, term,
                new PublicationService.CalendarEdit(start, end.plusWeeks(1), List.of(exam))))
                .isInstanceOfSatisfying(ScheduleConflict.class, c -> assertThat(c.code).isEqualTo("DATED_CHANGES_EXIST"));
        var rejected = changes.respond(school + 5, term, invite.id(), false, 0);
        publication.editTerm(admin, term, new PublicationService.CalendarEdit(start, end.plusWeeks(1), List.of(exam)));
        assertThat(lessons.date(admin, term, exam)).isEmpty();
        assertThat(db.queryForObject("select state from lesson_change where id=?", String.class, rejected.id()))
                .isEqualTo("REJECTED");
        publication.editTerm(admin, term, new PublicationService.CalendarEdit(start, end.plusWeeks(1), List.of()));
        var replacement = changes.createBatch(admin, term, new CoverBatch(covered.id(), covered.rev(), List.of(school + 5))).get(0);
        var acceptance = changes.respond(school + 5, term, replacement.id(), true, 0);
        approve(admin, acceptance, UUID.randomUUID());
        assertThat(timetables.week(school + 5, true, 2026, 2, term, exam)).hasSize(1)
                .allMatch(row -> row.getWeeklyLessonId() == covered.originId() && row.getTeacherId() == school + 5);
        assertThat(timetables.week(covered.actual().teacherId(), true, 2026, 2, term, exam)).isEmpty();
        assertThat(publication.publish(admin, term, selected).weeklyLessons()).isEqualTo(3);
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
    void frozenGradeLimitsRoundTripAndClassGradeChangesRequireRegeneration() {
        var limits = new GradeLastPeriod(1, Map.of(DayOfWeek.MON, 3, DayOfWeek.TUE, 0,
                DayOfWeek.WED, 0, DayOfWeek.THU, 0, DayOfWeek.FRI, 0));
        request = new Request(request.validatedLoadId(), null, null, Objective.BALANCED, List.of(limits));
        var result = generate();
        var poll = store.poll(admin, term, result.jobId());
        assertThat(poll.gradeLastPeriods()).containsExactly(limits);
        assertThat(poll.classGrades()).containsExactly(Map.entry(Long.toString(school), 1));
        assertThat(poll.registeredPeriods()).containsExactly(1, 2, 3);
        assertThat(poll.teachingSlots()).containsExactly(new Slot(DayOfWeek.MON, 1),
                new Slot(DayOfWeek.MON, 2), new Slot(DayOfWeek.MON, 3));
        db.update("update school_class set grade=2 where id=?", school);
        assertThatThrownBy(() -> publication.publish(admin, term, choose(result)))
                .isInstanceOf(ScheduleConflict.class);
        assertThat(store.poll(admin, term, result.jobId()).classGrades()).isEqualTo(poll.classGrades());
    }

    @Test
    void preGradeSnapshotStillPollsAndPublishesItsOriginalCommonCalendar() {
        var result = generate();
        db.update("update generation_job set snapshot=json_remove(snapshot,'$.classGrades','$.request.gradeLastPeriods') where id=?",
                result.jobId().toString());
        var poll = store.poll(admin, term, result.jobId());
        assertThat(poll.classGrades()).isNull();
        assertThat(poll.gradeLastPeriods()).isNull();
        assertThat(poll.teachingSlots()).isEqualTo(request.teachingSlots());
        assertThat(publication.publish(admin, term, choose(result)).weeklyLessons()).isEqualTo(3);
    }

    @Test
    void anUnassignedTeachersNewBlockDoesNotInvalidateTheChosenCandidate() {
        var result = generate();
        db.update("insert into teacher_availability(school_user_id,day_of_week,period_number) values(?,'MON',1)",
                school + 5);
        assertThat(publication.publish(admin, term, choose(result)).weeklyLessons()).isEqualTo(3);
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
        var assigned = result.candidates().get(0).placements().get(0);
        db.update("insert into teacher_availability(school_user_id,day_of_week,period_number) values(?,?,?)",
                Long.parseLong(assigned.teacherId()), assigned.slot().day().name(), assigned.slot().period());
        assertThatThrownBy(() -> publication.publish(admin, term, choose(result)))
                .isInstanceOfSatisfying(ScheduleConflict.class, c -> assertThat(c.code).isEqualTo("STALE_INPUT"));
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

    @RepeatedTest(3)
    void teacherBatchChoosesOneWinnerAndAdministratorAppliesSeparateCoverAndSwap() {
        var week = publish();
        var first = week.get(0);
        var b = week.get(1);
        var c = week.get(2);
        long owner = first.actual().teacherId();
        long spareA = school + 5, spareB = school + 6;
        var batch = new CoverBatch(first.id(), first.rev(), List.of(spareA, spareB));
        assertThatThrownBy(() -> changes.createBatch(spareA, term, batch))
                .isInstanceOf(com.rssolplan.edu.global.exception.ForbiddenException.class);
        assertThat(changes.candidates(owner, term, first.id(), first.rev(), ChangeService.Kind.SUBSTITUTE, null))
                .contains(spareA, spareB).doesNotContain(owner);
        assertThatThrownBy(() -> changes.createBatch(owner, term,
                new CoverBatch(first.id(), first.rev(), List.of(spareA, owner))))
                .isInstanceOf(ScheduleConflict.class);
        assertThat(changes.list(admin, term)).isEmpty();
        var group = changes.createBatch(owner, term, batch);
        assertThatThrownBy(() -> changes.createBatch(owner, term, batch))
                .isInstanceOfSatisfying(ScheduleConflict.class, e -> assertThat(e.code).isEqualTo("DUPLICATE_REQUEST"));
        var result = race(
                () -> changes.respond(spareA, term, group.get(0).id(), true, 0),
                () -> changes.respond(spareB, term, group.get(1).id(), true, 0));
        assertThat(result).filteredOn(ScheduleConflict.class::isInstance).hasSize(1);
        assertThat(changes.list(admin, term)).extracting(Change::state)
                .containsExactlyInAnyOrder("WITHDRAWN", "ACCEPTED");
        assertThat(lessons.get(term, first.id())).isEqualTo(first);
        var winner = result.stream().filter(Change.class::isInstance).map(Change.class::cast).findFirst().orElseThrow();
        assertThatThrownBy(() -> approve(owner, winner, UUID.randomUUID()))
                .isInstanceOf(com.rssolplan.edu.global.exception.ForbiddenException.class);
        var afterCover = approve(admin, winner, UUID.randomUUID()).lessons().get(0);
        assertThat(afterCover.actual().teacherId()).isEqualTo(winner.responder());
        assertThat(afterCover.planned()).isEqualTo(first.planned());
        assertThat(changes.candidates(b.actual().teacherId(), term, b.id(), b.rev(), ChangeService.Kind.SWAP, c.date())).contains(c.id());
        var exchange = swap(b, c);
        var afterSwap = approve(admin, exchange, UUID.randomUUID()).lessons();
        assertThat(afterSwap).hasSize(2);
        assertThat(lessons.get(term, b.id()).actual().periodId()).isEqualTo(c.actual().periodId());
        assertThat(lessons.get(term, c.id()).actual().periodId()).isEqualTo(b.actual().periodId());
        assertThat(db.queryForObject("select count(*) from notifications where school_id=? and type='SUBSTITUTE_FILLED_BROADCAST' and target_type='LESSON_CHANGE'", Long.class, school)).isEqualTo(1);
    }

    @Test
    void coverRechecksTheActualTeacherInAnotherClassAndIgnoresCancelledLessons() {
        var first = publish().get(0);
        long busy = school + 5;
        long released = school + 6;
        long period = first.actual().periodId();
        db.update("insert into school_class(id,school_id,academic_year,class_number,grade) values(?,?,2026,2,1)",
                school + 1, school);
        db.update("""
                insert into weekly_lesson(version_id,lesson_key,class_id,subject_id,teacher_id,weekday,period_id)
                values(?,'parallel-class',?,?,?,1,?)
                """, first.versionId(), school + 1, school, released, period);
        db.update("""
                insert into dated_lesson(origin_id,origin_date,teaching_date,planned,actual,rev)
                select id,?,?,json_object('teacherId',?,'periodId',?),json_object('teacherId',?,'periodId',?),1
                from weekly_lesson where version_id=? and lesson_key='parallel-class'
                """, monday, monday, released, period, busy, period, first.versionId());
        var parallel = lessons.date(admin, term, monday).stream()
                .filter(l -> l.classId() == school + 1).findFirst().orElseThrow();
        var batch = new CoverBatch(first.id(), first.rev(), List.of(busy));
        assertThat(changes.candidates(admin, term, first.id(), first.rev(), ChangeService.Kind.SUBSTITUTE, null))
                .contains(released).doesNotContain(busy);
        assertThatThrownBy(() -> changes.createBatch(admin, term, batch))
                .isInstanceOfSatisfying(ScheduleConflict.class, e -> assertThat(e.code).isEqualTo("SLOT_CONFLICT"));

        db.update("update dated_lesson set actual=json_set(actual,'$.cancelled',true),rev=rev+1 where id=?", parallel.id());
        assertThat(changes.candidates(admin, term, first.id(), first.rev(), ChangeService.Kind.SUBSTITUTE, null)).contains(busy);
        var accepted = cover(first, busy);
        db.update("update dated_lesson set actual=json_remove(actual,'$.cancelled'),rev=rev+1 where id=?", parallel.id());
        assertThatThrownBy(() -> approve(admin, accepted, UUID.randomUUID()))
                .isInstanceOfSatisfying(ScheduleConflict.class, e -> assertThat(e.code).isEqualTo("SLOT_CONFLICT"));
        assertThat(lessons.get(term, first.id())).isEqualTo(first);
    }

    @Test
    void crossDateSwapHonorsBothTeachersIncludingTheLinkedLoginAtListingAndApproval() {
        var first = publish().get(0);
        long owner = first.actual().teacherId();
        var second = lessons.date(admin, term, monday.plusWeeks(1)).stream()
                .filter(l -> l.actual().teacherId() != owner).findFirst().orElseThrow();
        long login = school + 5;
        db.update("""
                insert into school_teacher_roster(school_id,auth_email,display_name,school_user_id)
                values(?,'linked@example.invalid','연결 교사',?)
                """, school, login);
        db.update("""
                insert into roster_teaching_link(roster_id,teacher_school_user_id)
                select id,? from school_teacher_roster where school_id=? and school_user_id=?
                """, second.actual().teacherId(), school, login);
        String block = """
                insert into teacher_availability(school_user_id,day_of_week,period_number)
                select ?,'MON',period_number from period_setting where id=?
                """;
        var body = new NewChange(ChangeService.Kind.SWAP, first.id(), first.rev(), second.id(), second.rev(), null);
        for (var teacherAndPeriod : List.of(
                List.of(owner, second.actual().periodId()), List.of(login, first.actual().periodId()))) {
            long teacher = teacherAndPeriod.get(0);
            db.update(block, teacher, teacherAndPeriod.get(1));
            assertThat(changes.candidates(owner, term, first.id(), first.rev(), ChangeService.Kind.SWAP, second.date()))
                    .doesNotContain(second.id());
            assertThatThrownBy(() -> changes.create(owner, term, body))
                    .isInstanceOfSatisfying(ScheduleConflict.class, e -> assertThat(e.code).isEqualTo("SLOT_CONFLICT"));
            db.update("delete from teacher_availability where school_user_id=?", teacher);
        }
        assertThat(changes.candidates(owner, term, first.id(), first.rev(), ChangeService.Kind.SWAP, second.date()))
                .contains(second.id());
        var created = changes.create(owner, term, body);
        var accepted = changes.respond(login, term, created.id(), true, created.rev());
        db.update(block, login, first.actual().periodId());
        assertThatThrownBy(() -> approve(admin, accepted, UUID.randomUUID()))
                .isInstanceOfSatisfying(ScheduleConflict.class, e -> assertThat(e.code).isEqualTo("SLOT_CONFLICT"));
        assertThat(lessons.get(term, second.id())).isEqualTo(second);
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
    void publicCoverUsesFreeTeachersAtEachStepAndKeepsLongInvitationsAndLinkedIdentities() {
        long owner = school + 2, busy = school + 3, unavailable = school + 4;
        long teaching = school + 5, login = school + 6;
        for (long id = school + 20; id < school + 58; id++) {
            db.update("insert into users(id,active_school_id,username) values(?,?,'staff')", id, school);
            db.update("insert into school_user(id,school_id,user_id,position,employment_status) values(?,?,?,'TEACHER','HIRED')",
                    id, school, id);
        }
        db.update("""
                insert into school_teacher_roster(school_id,auth_email,display_name,school_user_id)
                values(?,'cover@example.invalid','연결 교사',?)
                """, school, login);
        db.update("""
                insert into roster_teaching_link(roster_id,teacher_school_user_id)
                select id,? from school_teacher_roster where school_id=? and school_user_id=?
                """, teaching, school, login);
        db.update("insert into school_class(id,school_id,academic_year,class_number,grade) values(?,?,2026,2,1)",
                school + 1, school);
        String slot = """
                insert into timetable(id,school_id,academic_year,semester,school_class_id,period_setting_id,
                    day_of_week,subject_id,teacher_id) values(?,?,2026,2,?,?,'MON',?,?)
                """;
        db.update(slot, school, school, school, school, school, owner);
        db.update(slot, school + 1, school, school + 1, school, school, busy);
        String block = "insert into teacher_availability(school_user_id,day_of_week,period_number) values(?,'MON',1)";
        db.update(block, unavailable);
        var invite = legacy.create(admin, new SubstituteCreateRequest(school, monday, "공개 모집"));
        var stored = db.queryForObject("select receiver_user_ids from substitute_requests where id=?", String.class, invite.id());
        assertThat(stored.length()).isGreaterThan(255);
        assertThat(Arrays.asList(stored.split(","))).contains("" + teaching)
                .doesNotContain("" + login, "" + owner, "" + unavailable, "" + busy);
        var recipients = db.queryForList("select user_id from notifications where school_id=? and substitute_request_id=?",
                Long.class, school, invite.id());
        assertThat(recipients).hasSize(39).contains(login).doesNotContain(teaching, busy, unavailable);
        assertThat(legacy.getRequests(busy, "OPEN")).isEmpty();
        assertThat(legacy.getRequests(login, "OPEN")).extracting("id").contains(invite.id());
        var accept = new SubstituteRespondRequest("ACCEPT");
        assertThatThrownBy(() -> legacy.respond(teaching, invite.id(), accept)).isInstanceOf(ForbiddenException.class);
        db.update(block, login);
        assertThatThrownBy(() -> legacy.respond(login, invite.id(), accept)).isInstanceOf(ScheduleConflict.class);
        db.update("delete from teacher_availability where school_user_id=?", login);
        var accepted = legacy.respond(login, invite.id(), accept);
        assertThat(accepted.candidateSchoolUserId()).isEqualTo(teaching);
        var approval = new SubstituteApprovalRequest("APPROVE");
        db.update(block, login);
        assertThatThrownBy(() -> legacy.approve(admin, accepted.responseId(), approval)).isInstanceOf(ScheduleConflict.class);
        db.update("delete from teacher_availability where school_user_id=?", login);
        legacy.approve(admin, accepted.responseId(), approval);
        var parallel = legacy.create(admin, new SubstituteCreateRequest(school + 1, monday, "다른 학급"));
        assertThat(legacy.getRequests(owner, "OPEN")).extracting("id").contains(parallel.id());
        assertThat(legacy.getRequests(login, "OPEN")).isEmpty();
    }

    @Test
    void publicRecruitmentBindsPublishedOccurrenceAndLetsAdminChooseAmongVolunteers() {
        var row = publish().get(0);
        var candidates = changes.candidates(admin, term, row.id(), row.rev(), ChangeService.Kind.SUBSTITUTE, null);
        long first = candidates.get(0), second = candidates.get(1);
        var input = new SubstituteCreateRequest(null, row.date(), "공개 모집", term, row.id(), row.rev());
        var request = legacy.create(admin, input);
        assertThat(request.datedLessonId()).isEqualTo(row.id());
        assertThat(request.timetableId()).isNull();
        assertThatThrownBy(() -> legacy.create(admin, input)).isInstanceOf(ScheduleConflict.class);
        var accept = new SubstituteRespondRequest("ACCEPT");
        legacy.respond(first, request.id(), accept);
        var selected = legacy.respond(second, request.id(), accept);
        assertThat(legacy.getRequests(admin, "OPEN").get(0).responses()).hasSize(2);
        assertThat(legacy.getRequests(first, "OPEN").get(0).responses()).hasSize(1);
        assertThat(lessons.get(term, row.id()).actual()).isEqualTo(row.actual());
        var key = UUID.randomUUID();
        var approved = new SubstituteApprovalRequest("APPROVE");
        var outcome = legacy.approve(admin, selected.responseId(), approved, key);
        assertThat(legacy.approve(admin, selected.responseId(), approved, key)).isEqualTo(outcome);
        assertThat(outcome.requestStatus()).isEqualTo("FILLED");
        assertThat(lessons.get(term, row.id()).actual().teacherId()).isEqualTo(second);
        assertThat(lessons.get(term, row.id()).rev()).isEqualTo(row.rev()+1);
        assertThatThrownBy(() -> legacy.create(admin, input)).isInstanceOf(ScheduleConflict.class);
        var updated = legacy.create(admin, new SubstituteCreateRequest(null, row.date(), "", term, row.id(), row.rev()+1));
        assertThat(legacy.withdraw(admin, updated.id()).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> legacy.respond(first, updated.id(), accept)).isInstanceOf(IllegalStateException.class);
    }

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
