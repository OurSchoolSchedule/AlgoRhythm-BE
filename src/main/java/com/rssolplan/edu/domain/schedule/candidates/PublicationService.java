package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import com.rssolplan.edu.domain.schedule.operations.*;
import com.rssolplan.edu.global.exception.*;

import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
class PublicationService {
    private final JdbcTemplate db;
    private final ScheduleAccess access;
    private final GenerationStore jobs;
    private final CandidateInputService inputs;
    private final ScheduleJson json;
    private final TermCalendar calendar;

    record Publish(
            @NotNull UUID jobId,
            @Pattern(regexp = "[a-f0-9]{64}") @NotNull String candidateId,
            @NotNull LocalDate effectiveFrom,
            @NotNull @PositiveOrZero Long expectedPublishRev) {}

    record Published(long versionId, long publishRev, int weeklyLessons) {}

    record TermSummary(long termId, int academicYear, int semester, LocalDate startDate, LocalDate endDate,
                       List<LocalDate> excludedDates) {
        TermSummary(long termId, int year, int semester, LocalDate start, LocalDate end) {
            this(termId, year, semester, start, end, List.of());
        }
    }

    @Transactional(readOnly = true)
    public List<TermSummary> terms(long user) {
        return calendar.terms(access.member(user, false).getSchool().getId());
    }

    record NewTerm(@Min(1900) @Max(9999) int academicYear, @Min(1) @Max(2) int semester,
                   @NotNull LocalDate startDate, @NotNull LocalDate endDate, List<@NotNull LocalDate> excludedDates) {
        NewTerm(int year, int semester, LocalDate start, LocalDate end) {
            this(year, semester, start, end, List.of());
        }
    }

    record CalendarState(@NotNull LocalDate startDate, @NotNull LocalDate endDate,
                         @NotNull List<@NotNull LocalDate> excludedDates) {}

    record CalendarEdit(@NotNull LocalDate startDate, @NotNull LocalDate endDate,
                        @NotNull List<@NotNull LocalDate> excludedDates, @jakarta.validation.Valid CalendarState expectedCalendar) {
        CalendarEdit(LocalDate start, LocalDate end, List<LocalDate> excluded) {
            this(start, end, excluded, null);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long term(long user, NewTerm t) {
        long school = access.member(user, true).getSchool().getId();
        access.lockSchool(school);
        return calendar.create(school, t);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TermSummary editTerm(long user, long term, CalendarEdit edit) {
        long school = access.schoolOfTerm(user, term, true);
        access.lockSchool(school);
        return calendar.edit(school, term, edit);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Published publish(long user, long term, Publish request) {
        long school = access.schoolOfTerm(user, term, true);
        access.lockSchool(school);
        var job = jobs.get(request.jobId());
        if (job.schoolId() != school || (job.termId() != null && job.termId() != term))
            throw new NotFoundException("이 학기의 후보가 아닙니다.");
        var prior =
                db.queryForList(
                        """
select r.candidate_id,v.id,v.version_no,v.effective_from,v.term_id from publication_receipt r
join schedule_version v on v.id=r.version_id where r.job_id=?
""",
                        request.jobId().toString());
        if (!prior.isEmpty()) {
            var row = prior.get(0);
            if (((Number) row.get("term_id")).longValue() != term
                    || !row.get("candidate_id").equals(request.candidateId())
                    || !row.get("effective_from")
                            .toString()
                            .equals(request.effectiveFrom().toString()))
                throw new ScheduleConflict(
                        "JOB_ALREADY_PUBLISHED", null, "이 작업은 이미 다른 선택으로 확정되었습니다.");
            return receipt(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("version_no")).longValue());
        }
        long rev = jobs.revision(term);
        if (request.expectedPublishRev() != rev || job.baseRev() != rev)
            throw new ScheduleConflict("STALE_PUBLICATION", rev, "새 버전 기준으로 다시 생성하세요.");
        if (!"READY".equals(job.state()))
            throw new ScheduleConflict("JOB_NOT_READY", null, "비교 가능한 후보가 없습니다.");
        var chosen =
                job.result().candidates().stream()
                        .filter(c -> c.candidateId().equals(request.candidateId()))
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException("작업에서 후보를 찾을 수 없습니다."));
        var stored = job.snapshot();
        var fresh = inputs.refresh(user, term, stored);
        if (!stored.slots().equals(fresh.slots())
                || !stored.bells().equals(fresh.bells())
                || !stored.blocked().equals(fresh.blocked()))
            throw new ScheduleConflict("STALE_INPUT", null, "명부·교시·불가 시간이 변경되었습니다. 다시 생성하세요.");
        verify(fresh.problem(), chosen);
        var dates =
                db.queryForMap(
                        "select start_date,end_date,semester from timetable_set where id=?", term);
        LocalDate from = request.effectiveFrom(),
                end = LocalDate.parse(dates.get("end_date").toString());
        if (from.isBefore(LocalDate.parse(dates.get("start_date").toString())) || from.isAfter(end))
            throw new BadRequestException("적용일은 학기 기간 안이어야 합니다.");
        long changed =
                db.queryForObject(
                        """
select count(*) from dated_lesson d join weekly_lesson w on w.id=d.origin_id join schedule_version v on v.id=w.version_id
where v.term_id=? and d.rev>0 and (d.teaching_date>=? or d.origin_date>=?)
""",
                        Long.class,
                        term,
                        from,
                        from);
        if (changed > 0
                || legacyApprovals(
                        school, stored.year(), (Number) dates.get("semester"), from, end))
            throw new ScheduleConflict("DATED_CHANGES_EXIST", rev, "적용 기간의 기존 승인 내역을 먼저 대조하세요.");
        long load = loadVersion(term, access.member(user, true).getId(), stored);
        db.update(
                "insert into schedule_version(term_id,load_id,version_no,effective_from)"
                    + " values(?,?,?,?)",
                term,
                load,
                rev + 1,
                from);
        long version = lastId();
        var rows = new ArrayList<Object[]>();
        for (var a : chosen.placements()) {
            long period = fresh.bells().get(a.slot().period()).id();
            rows.add(
                    new Object[] {
                        version,
                        "L" + (rows.size() + 1),
                        a.classId(),
                        a.subjectId(),
                        a.teacherId(),
                        a.slot().day().getValue(),
                        period
                    });
        }
        db.batchUpdate(
                "insert into"
                    + " weekly_lesson(version_id,lesson_key,class_id,subject_id,teacher_id,weekday,period_id)"
                    + " values(?,?,?,?,?,?,?)",
                rows);
        var exclusions = calendar.exclusions(term);
        for (LocalDate date = from; !date.isAfter(end); date = date.plusDays(1)) {
            if (!exclusions.contains(date)) calendar.materialize(version, date);
        }
        db.update(
                "insert into publication_receipt(job_id,candidate_id,version_id) values(?,?,?)",
                request.jobId().toString(),
                request.candidateId(),
                version);
        return new Published(version, rev + 1, rows.size());
    }

    private boolean legacyApprovals(
            long school, int year, Number semester, LocalDate from, LocalDate end) {
        return db.queryForObject(
                """
select exists(select 1 from substitute_requests s join timetable t on t.id=s.timetable_id
    where s.school_id=? and t.academic_year=? and t.semester=? and s.status='FILLED' and s.substitute_date between ? and ?)
or exists(select 1 from timetable_swap_requests s join timetable t on t.id=s.requester_timetable_id
    where s.school_id=? and t.academic_year=? and t.semester=? and s.manager_approval_status='APPROVED'
    and (s.requester_date between ? and ? or s.receiver_date between ? and ?))
""",
                Boolean.class,
                school,
                year,
                semester,
                from,
                end,
                school,
                year,
                semester,
                from,
                end,
                from,
                end);
    }

    private long loadVersion(long term, long creator, CandidateInputService.Snapshot s) {
        String digest;
        try {
            digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    json.write(s.load().demands())
                                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        var ids =
                db.queryForList(
                        "select id from load_import where term_id=? and normalized_sha=?",
                        Long.class,
                        term,
                        digest);
        if (!ids.isEmpty()) return ids.get(0);
        int n =
                db.queryForObject(
                        "select coalesce(max(version_no),0)+1 from load_import where term_id=?",
                        Integer.class,
                        term);
        db.update(
                "insert into load_import(term_id,created_by,version_no,normalized_sha,sheet_name)"
                    + " values(?,?,?,?,?)",
                term,
                creator,
                n,
                digest,
                s.load().sheet());
        long id = lastId();
        db.batchUpdate(
                "insert into"
                    + " load_demand(load_id,teacher_id,subject_id,class_id,weekly_periods,source_cell)"
                    + " values(?,?,?,?,?,?)",
                s.load().demands().stream()
                        .map(
                                d ->
                                        new Object[] {
                                            id,
                                            d.teacherId(),
                                            d.subjectId(),
                                            d.classId(),
                                            d.weeklyPeriods(),
                                            d.sourceCell()
                                        })
                        .toList());
        return id;
    }

    static void verify(TimetableProblem problem, Candidate chosen) {
        Map<List<String>, Integer> indices = new HashMap<>();
        for (int i = 0; i < problem.rows.size(); i++) {
            var d = problem.rows.get(i).demand();
            indices.put(List.of(d.teacherId(), d.subjectId(), d.classId()), i);
        }
        var layout = CandidateRanking.emptySets(indices.size());
        for (var a : chosen.placements()) {
            Integer i = indices.get(List.of(a.teacherId(), a.subjectId(), a.classId()));
            int slot = problem.slots.indexOf(a.slot());
            if (i == null || slot < 0 || layout[i].get(slot))
                throw new CandidateRanking.InvalidLayout();
            layout[i].set(slot);
        }
        var verifier = new CandidateRanking();
        verifier.offer(problem, layout, new SearchBudget(Duration.ofSeconds(5), System::nanoTime));
        if (!verifier.best.get(0).candidateId().equals(chosen.candidateId()))
            throw new CandidateRanking.InvalidLayout();
    }

    private long lastId() {
        return db.queryForObject("select last_insert_id()", Long.class);
    }

    private Published receipt(long id, long rev) {
        return new Published(
                id,
                rev,
                db.queryForObject(
                        "select count(*) from weekly_lesson where version_id=?",
                        Integer.class,
                        id));
    }
}
