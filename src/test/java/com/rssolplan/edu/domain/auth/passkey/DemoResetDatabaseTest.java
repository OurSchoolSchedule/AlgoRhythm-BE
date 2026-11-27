package com.rssolplan.edu.domain.auth.passkey;

import static org.assertj.core.api.Assertions.*;

import com.rssolplan.edu.domain.demoreset.*;
import com.rssolplan.edu.domain.schedule.candidates.School42Round;
import com.rssolplan.edu.domain.schedule.candidates.School42Round.TimetableScope;
import com.rssolplan.edu.domain.schedule.operations.ScheduleConflict;
import com.yubico.webauthn.data.ByteArray;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "demo.reset.operator=presenter@example.invalid",
      "demo.reset.database=demo_reset_test"
    })
@PasskeyTestSchema
@EnabledIfEnvironmentVariable(named = "DEMO_RESET_DB_TEST", matches = "1")
class DemoResetDatabaseTest {
  private static final String PIN = UUID.randomUUID().toString().substring(0, 8);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry r) {
    r.add("demo.reset.pin-hash", () -> BCrypt.hashpw(PIN, BCrypt.gensalt(4)));
  }

  @Autowired JdbcTemplate db;
  @Autowired DemoResetService service;
  @Autowired School42Round fixture;
  @Autowired PasskeyFlows flows;
  @Autowired PasskeyStore keys;
  @Autowired TransactionTemplate tx;
  @Autowired StringRedisTemplate redis;

  @Autowired DemoRound round;

  private String grant(long user) {
    return tx.execute(
        s ->
            flows.put(
                "approval",
                new PasskeyFlows.State(
                    PasskeyFlows.Action.DEMO_RESET, user, keys.lock(user).revision(), null, null)));
  }

  private long count(String table) {
    return db.queryForObject("select count(*) from " + table, Long.class);
  }

  private void timetable(long school, long term, int id) {
    db.update(
        "insert into"
            + " generation_job(id,school_id,term_id,academic_year,base_rev,snapshot,state,created_at,deadline)"
            + " values(?,?,?,2026,0,'{}','READY',now(),now())",
        "job-" + id,
        school,
        term);
    db.update(
        "insert into schedule_version(id,term_id,version_no,effective_from) values"
            + " (?,?,1,'2026-11-23')",
        id,
        term);
    db.update("insert into publication_receipt values(?,?,?)", "job-" + id, "candidate", id);
    if (school == 540001) {
      db.update(
          "insert into"
              + " weekly_lesson(id,version_id,lesson_key,class_id,subject_id,teacher_id,weekday,period_id)"
              + " values(?,?,'A',544001,543001,542001,1,546001)",
          id,
          id);
      db.update(
          "insert into dated_lesson(id,origin_id,origin_date,teaching_date,planned,actual)"
              + " values(?,?,'2026-11-23','2026-11-23','{}','{}')",
          id,
          id);
    }
  }

  @Test
  void twoRoundsClearOnlyTheDemoTimetableAndKeepExistingIdentitiesAndInputs() {
    assertThat(db.queryForObject("select database()", String.class)).isEqualTo("demo_reset_test");
    fixture.prepareTable();
    fixture.open();
    db.update("insert into school(id,name) values (700001,'다른 학교')");
    db.update(
        "insert into timetable_set(id, school_id,academic_year,semester,start_date,end_date) values"
            + " (700002,700001,2026,2,'2026-11-23','2026-12-04'),(700003,540001,2027,1,'2027-03-01','2027-07-31')");
    timetable(700001, 700002, 100);
    timetable(540001, 700003, 101);
    db.update(
        "insert into users(provider,provider_id,active_school_id) values"
            + " ('email','presenter@example.invalid',540001)");
    long user =
        db.queryForObject(
            "select id from users where provider_id='presenter@example.invalid'", Long.class);
    db.update("insert into roster_verified_account(user_id,auth_email) values(?,?)", user, "presenter@example.invalid");
    db.update(
        "insert into school_user(school_id,user_id,position,employment_status) values"
            + " (540001,?,'ADMIN','HIRED')",
        user);
    db.update(
        "insert into user_refresh_tokens(user_id,refresh_token,expires_at) values (?,"
            + " 'test-refresh', DATE_ADD(NOW(),INTERVAL 1 DAY))",
        user);
    for (long who : new long[] {user, 541004}) {
      tx.executeWithoutResult(
          s -> {
            keys.lock(who);
            keys.insert(
                who,
                new ByteArray(PasskeyStore.randomBytes()),
                new ByteArray(PasskeyStore.randomBytes()),
                3,
                false,
                false);
          });
    }
    var presenterDevices = keys.devices(user);
    var teacherDevices = keys.devices(541004);
    String epoch = round.prefix();
    String key = epoch + "EMAIL_AUTH:fresh@example.invalid";
    redis.opsForValue().set(key, "test-proof");
    db.update(
        "insert into load_import(id,term_id,version_no,created_by,normalized_sha,sheet_name)"
            + " values(90,547001,1,542000,'digest','시수표')");
    db.update(
        "insert into load_demand(load_id,teacher_id,subject_id,class_id,weekly_periods,source_cell)"
            + " values(90,542001,543001,544001,4,'A1')");
    String load = UUID.randomUUID().toString();
    db.update("update school42_rehearsal set validated_load=? where school_id=540001", load);
    for (int i = 0; i < 2; i++) {
      timetable(540001, 547001, 102 + i);
      var scope = service.preview(user);
      assertThat(scope).isEqualTo(new TimetableScope(540001, 547001, i + 1, 1, 1, School42Round.Mode.TIMETABLE, 0, 1, 1, 0, 0, 1, null));
      assertThatThrownBy(() -> service.preview(541004L)).isInstanceOf(SecurityException.class);
      assertThatThrownBy(() -> service.preview(541000L)).isInstanceOf(SecurityException.class);
      assertThatThrownBy(() -> service.reset(user, scope, PIN, "invalid"))
          .isInstanceOf(PasskeyProblem.class);
      if (i == 0) {
        assertThatThrownBy(() -> service.reset(user, scope, "wrong", grant(user)))
            .isInstanceOf(IllegalArgumentException.class);
        var other = new TimetableScope(700001, 700002, scope.round(), 1, 1, School42Round.Mode.TIMETABLE, 0, 1, 1, 0, 0, 1, null);
        assertThatThrownBy(() -> service.reset(user, other, PIN, grant(user)))
            .isInstanceOf(ScheduleConflict.class);
        db.execute(
            "create trigger test_rollback before delete on generation_job for each row signal"
                + " sqlstate '45000' set message_text='rollback'");
        try {
          assertThatThrownBy(() -> service.reset(user, scope, PIN, grant(user)))
              .isInstanceOf(RuntimeException.class);
          assertThat(service.preview(user)).isEqualTo(scope);
          assertThat(count("dated_lesson")).isEqualTo(2);
        } finally {
          db.execute("drop trigger test_rollback");
        }
      }
      String approval = grant(user);
      assertThat(service.reset(user, scope, PIN, approval))
          .isEqualTo(new TimetableScope(540001, 547001, i + 2, 0, 0, School42Round.Mode.TIMETABLE, 0, 0, 0, 0, 0, 0, null));
      assertThatThrownBy(() -> service.reset(user, scope, PIN, approval))
          .isInstanceOf(PasskeyProblem.class);
      redis.delete("demo-reset:attempts:demo_reset_test:" + user);
      assertThatThrownBy(() -> service.reset(user, scope, PIN, grant(user)))
          .isInstanceOf(ScheduleConflict.class);

      assertThat(count("users")).isEqualTo(44);
      assertThat(count("school_user")).isEqualTo(44);
      assertThat(count("school")).isEqualTo(2);
      assertThat(count("timetable_set")).isEqualTo(3);
      assertThat(
              db.queryForObject(
                  "select refresh_token from user_refresh_tokens where user_id=?",
                  String.class,
                  user))
          .isEqualTo("test-refresh");
      assertThat(keys.devices(user)).isEqualTo(presenterDevices).hasSize(1);
      assertThat(keys.devices(541004)).isEqualTo(teacherDevices).hasSize(1);
      assertThat(round.prefix()).isEqualTo(epoch);
      assertThat(redis.opsForValue().get(key)).isEqualTo("test-proof");
      assertThat(count("load_import")).isOne();
      assertThat(count("load_demand")).isOne();
      assertThat(
              db.queryForObject(
                  "select validated_load from school42_rehearsal where school_id=540001",
                  String.class))
          .isEqualTo(load);
      assertThat(db.queryForList("select id from schedule_version order by id", Long.class))
          .containsExactly(100L, 101L);
      assertThat(
              db.queryForList(
                  "select job_id from publication_receipt order by job_id", String.class))
          .containsExactly("job-100", "job-101");
      assertThat(db.queryForList("select id from generation_job order by id", String.class))
          .containsExactly("job-100", "job-101");
      assertThat(db.queryForList("select id from weekly_lesson", Long.class)).containsExactly(101L);
      assertThat(db.queryForList("select id from dated_lesson", Long.class)).containsExactly(101L);
    }
  }
}
