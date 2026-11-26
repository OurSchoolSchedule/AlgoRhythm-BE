package com.rssolplan.edu.domain.demoreset;

import com.rssolplan.edu.domain.auth.passkey.PasskeyService;
import com.rssolplan.edu.domain.auth.passkey.PasskeyStore;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DemoResetService {
  private final JdbcTemplate db;
  private final StringRedisTemplate redis;
  private final PasskeyStore identities;
  private final PasskeyService passkeys;
  private final DemoRound round;

  @Value("${demo.reset.operator:}")
  private String operator;

  @Value("${demo.reset.database:}")
  private String database;

  @Value("${demo.reset.pin-hash:}")
  private String pinHash;

  // Children precede parents; leave schema, sequences and unrelated tables intact.
  private static final List<String> DATA =
      List.of(
          "lesson_approval",
          "lesson_change",
          "legacy_approval_input",
          "legacy_lesson_rev",
          "publication_receipt",
          "dated_lesson",
          "weekly_lesson",
          "schedule_version",
          "load_demand",
          "load_import",
          "generation_job",
          "substitute_responses",
          "substitute_requests",
          "timetable_swap_requests",
          "notifications",
          "todo",
          "timetable",
          "timetable_request",
          "roster_import_preview",
          "roster_term_subject",
          "roster_term",
          "school_teacher_roster",
          "teacher_availability",
          "teacher_attendance",
          "school_class",
          "subject",
          "period_setting",
          "school_setting",
          "timetable_set",
          "school_user",
          "user_refresh_tokens",
          "passkey_credential",
          "passkey_account",
          "roster_verified_account",
          "email_verification_codes",
          "email_verification_history",
          "users",
          "school");
  private static final DefaultRedisScript<Long> ATTEMPTS =
      new DefaultRedisScript<>(
          "local n=redis.call('incr',KEYS[1]); if n==1 then redis.call('expire',KEYS[1],300) end;"
              + " return n",
          Long.class);

  public record Scope(long revision, long users, long schools) {}

  private void authorize(Long user) {
    if (user == null
        || operator.isBlank()
        || pinHash.isBlank()
        || database.isBlank()
        || !database.equals(db.queryForObject("select database()", String.class))
        || !user.equals(identities.verifiedUserId(operator))
        || !Boolean.TRUE.equals(
            db.queryForObject(
                """
                select exists(select 1 from school_user m join users u on u.id=m.user_id and u.active_school_id=m.school_id
                where u.id=? and m.position='ADMIN' and m.employment_status='HIRED')
                """,
                Boolean.class,
                user))) {
      throw new SecurityException("지정된 시연 관리자만 전체 초기화를 실행할 수 있습니다.");
    }
  }

  private Scope scope() {
    return new Scope(
        round.revision(),
        db.queryForObject("select count(*) from users", Long.class),
        db.queryForObject("select count(*) from school", Long.class));
  }

  @Transactional(readOnly = true)
  public Scope preview(Long user) {
    authorize(user);
    return scope();
  }

  @Transactional(isolation = Isolation.SERIALIZABLE)
  public Scope reset(Long user, Scope expected, String password, String approval) {
    authorize(user);
    round.lock();
    passkeys.approveDemoReset(user, approval);
    Long attempts =
        redis.execute(ATTEMPTS, List.of("demo-reset:attempts:" + database + ":" + user));
    if (attempts == null || attempts > 5)
      throw new IllegalStateException("초기화 시도 횟수를 초과했습니다. 5분 뒤 다시 시도하세요.");
    if (!BCrypt.checkpw(password, pinHash))
      throw new IllegalArgumentException("초기화 비밀번호가 일치하지 않습니다.");
    Scope before = scope();
    if (!before.equals(expected))
      throw new IllegalStateException("회원·학교 또는 회차가 바뀌었습니다. 초기화 범위를 다시 확인하세요.");
    if (Boolean.TRUE.equals(
        db.queryForObject(
            "select exists(select 1 from generation_job where state in ('QUEUED','RUNNING'))",
            Boolean.class))) throw new IllegalStateException("진행 중인 시간표 편성이 끝난 뒤 다시 시도하세요.");

    var tables = checkedTables();
    if (tables.contains("school42_rehearsal"))
      db.update("update school42_rehearsal set round_no=-abs(round_no)-1, validated_load=null");
    for (String table : DATA) {
      if (tables.contains(table)) db.update("delete from " + table);
    }
    round.advance();
    log.info(
        "Demo reset by user {}: round {}, {} users and {} schools",
        user,
        before.revision(),
        before.users(),
        before.schools());
    return before;
  }

  // On deployment check the same database boundary used by the write, without deleting data.
  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void checkConfiguration() {
    if (operator.isBlank() || database.isBlank() || pinHash.isBlank()) return;
    try {
      if (!database.equals(db.queryForObject("select database()", String.class)))
        throw new IllegalStateException("configured schema mismatch");
      checkedTables();
      log.info("Demo reset schema check passed");
    } catch (RuntimeException e) {
      log.warn("Demo reset schema check failed: {}", e.getMessage());
    }
  }

  private Set<String> checkedTables() {
    if (Boolean.TRUE.equals(
        db.queryForObject(
            """
            select exists(select 1 from information_schema.key_column_usage
            where referenced_table_schema=database() and table_schema<>database())
            """,
            Boolean.class))) throw new IllegalStateException("다른 DB와 연결되어 있어 초기화를 중단했습니다.");
    var tables =
        new HashSet<>(
            db.queryForList(
                "select table_name from information_schema.tables where table_schema=database() and"
                    + " engine='InnoDB'",
                String.class));
    // Fail closed on an unfamiliar table, or a nontransactional application table.
    var names =
        db.queryForList(
            "select table_name from information_schema.tables where table_schema=database() and"
                + " table_type='BASE TABLE'",
            String.class);
    for (String table : names) {
      if (Set.of("flyway_schema_history", "databasechangelog", "databasechangeloglock")
          .contains(table)) continue;
      if (!(DATA.contains(table)
              || table.equals("school42_rehearsal")
              || table.equals("demo_reset_round"))
          || !tables.contains(table)) {
        log.warn("Demo reset declined for unregistered or nontransactional table {}", table);
        throw new IllegalStateException("초기화 범위에 등록되지 않은 DB 구조가 있습니다. 데이터가 보존됐습니다.");
      }
    }
    return tables;
  }
}
