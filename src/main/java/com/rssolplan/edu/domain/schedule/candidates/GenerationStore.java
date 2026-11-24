package com.rssolplan.edu.domain.schedule.candidates;

import static com.rssolplan.edu.domain.schedule.candidates.CandidateApi.*;

import com.rssolplan.edu.domain.schedule.operations.*;
import com.rssolplan.edu.global.exception.NotFoundException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
@RequiredArgsConstructor
class GenerationStore {
    private final JdbcTemplate db;
    private final ScheduleAccess access;
    private final ScheduleJson json;

    record Ticket(UUID jobId, long expectedPublishRev) {}

    record Stored(
            UUID jobId,
            Long termId,
            long schoolId,
            long baseRev,
            String state,
            CandidateInputService.Snapshot snapshot,
            Response result) {}

    record Poll(UUID jobId, String state, long expectedPublishRev, Response result) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Ticket insert(
            CandidateInputService.Snapshot snapshot, UUID initialToken, long remainingMicros) {
        access.lockSchool(snapshot.schoolId());
        expire();
        var active =
                db.queryForList(
                        "select id from generation_job where school_id=? and state in"
                            + " ('QUEUED','RUNNING') limit 1",
                        String.class,
                        snapshot.schoolId());
        if (!active.isEmpty())
            throw new ScheduleConflict(
                    "GENERATION_ACTIVE", null, "학교에서 이미 편성 중입니다.", active.get(0));
        long base = snapshot.termId() == null ? 0 : revision(snapshot.termId());
        UUID id = UUID.randomUUID();
        db.update(
                """
insert into generation_job(id,school_id,term_id,academic_year,base_rev,snapshot,state,created_at,deadline,run_token,lease_until)
values(?,?,?,?,?,?,?,utc_timestamp(6),timestampadd(microsecond,?,utc_timestamp(6)),?,timestampadd(second,12,utc_timestamp(6)))
""",
                id.toString(),
                snapshot.schoolId(),
                snapshot.termId(),
                snapshot.year(),
                base,
                json.write(snapshot),
                initialToken == null ? "QUEUED" : "RUNNING",
                remainingMicros,
                initialToken == null ? null : initialToken.toString());
        return new Ticket(id, base);
    }

    long revision(long term) {
        return db.queryForObject(
                "select coalesce(max(version_no),0) from schedule_version where term_id=?",
                Long.class,
                term);
    }

    void expire() {
        db.update(
                """
update generation_job set state='TIME_LIMIT_NO_SOLUTION',run_token=null
where state in ('QUEUED','RUNNING') and deadline < timestampadd(second,-2,utc_timestamp(6))
""");
    }

    Optional<UUID> next() {
        expire();
        return db
                .queryForList(
                        """
                        select id from generation_job where deadline>utc_timestamp(6)
                        and (state='QUEUED' or (state='RUNNING' and lease_until<utc_timestamp(6)))
                        order by created_at limit 1
                        """,
                        String.class)
                .stream()
                .findFirst()
                .map(UUID::fromString);
    }

    boolean claim(UUID id, UUID token) {
        return db.update(
                        """
update generation_job set state='RUNNING',run_token=?,lease_until=timestampadd(second,12,utc_timestamp(6))
where id=? and deadline>utc_timestamp(6) and (state='QUEUED' or (state='RUNNING' and lease_until<utc_timestamp(6)))
""",
                        token.toString(),
                        id.toString())
                == 1;
    }

    void renew(UUID id, UUID token) {
        db.update(
                "update generation_job set lease_until=timestampadd(second,12,utc_timestamp(6))"
                    + " where id=? and run_token=? and state='RUNNING' and"
                    + " lease_until>=utc_timestamp(6) and deadline>utc_timestamp(6)",
                id.toString(),
                token.toString());
    }

    void requeue(UUID id, UUID token) {
        db.update(
                "update generation_job set state='QUEUED',run_token=null where id=? and run_token=?"
                    + " and state='RUNNING'",
                id.toString(),
                token.toString());
    }

    long remainingMicros(UUID id) {
        return db.queryForObject(
                "select timestampdiff(microsecond,utc_timestamp(6),deadline) from generation_job"
                    + " where id=?",
                Long.class,
                id.toString());
    }

    boolean finish(UUID id, UUID token, String state, Response result) {
        return db.update(
                        "update generation_job set state=?,result=?,run_token=null,lease_until=null"
                            + " where id=? and state='RUNNING' and run_token=? and"
                            + " lease_until>=utc_timestamp(6)",
                        state,
                        result == null ? null : json.write(result),
                        id.toString(),
                        token.toString())
                == 1;
    }

    Stored get(UUID id) {
        return db
                .query(
                        "select * from generation_job where id=?",
                        (r, n) ->
                                new Stored(
                                        id,
                                        r.getObject("term_id", Long.class),
                                        r.getLong("school_id"),
                                        r.getLong("base_rev"),
                                        r.getString("state"),
                                        json.read(
                                                r.getString("snapshot"),
                                                CandidateInputService.Snapshot.class),
                                        r.getString("result") == null
                                                ? null
                                                : json.read(r.getString("result"), Response.class)),
                        id.toString())
                .stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("생성 작업을 찾을 수 없습니다."));
    }

    Poll poll(long user, long term, UUID id) {
        long school = access.schoolOfTerm(user, term, true);
        return db
                .query(
                        "select state,base_rev,result from generation_job where id=? and term_id=?"
                            + " and school_id=?",
                        (r, n) ->
                                new Poll(
                                        id,
                                        r.getString(1),
                                        r.getLong(2),
                                        r.getString(3) == null
                                                ? null
                                                : json.read(r.getString(3), Response.class)),
                        id.toString(),
                        term,
                        school)
                .stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("생성 작업을 찾을 수 없습니다."));
    }
}
