package com.rssolplan.edu.domain.auth.passkey;

import static org.assertj.core.api.Assertions.*;

import com.rssolplan.edu.domain.demoreset.*;
import com.rssolplan.edu.domain.schedule.candidates.School42Round;
import com.rssolplan.edu.global.security.email_verification.EmailService;
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
  @Autowired EmailService email;
  @Autowired DemoRound round;

  private String grant(long user) {
    return tx.execute(
        s ->
            flows.put(
                "approval",
                new PasskeyFlows.State(
                    PasskeyFlows.Action.DEMO_RESET, user, keys.lock(user).revision(), null, null)));
  }

  @Test
  void twoRoundsRejectUnauthorizedOrStaleRequestsAndRemoveEveryUser() {
    assertThat(db.queryForObject("select database()", String.class)).isEqualTo("demo_reset_test");
    assertThat(db.getDataSource()).isNotNull();
    fixture.prepareTable();
    long priorUser = 0;
    for (int i = 0; i < 2; i++) {
      fixture.open();
      db.update(
          "insert into users(provider,provider_id,active_school_id) values"
              + " ('email','presenter@example.invalid',540001)");
      long user =
          db.queryForObject(
              "select id from users where provider_id='presenter@example.invalid'", Long.class);
      assertThat(user).isGreaterThan(priorUser);
      priorUser = user;
      db.update(
          "insert into school_user(school_id,user_id,position,employment_status) values"
              + " (540001,?,'ADMIN','HIRED')",
          user);
      db.update(
          "insert into user_refresh_tokens(user_id,refresh_token,expires_at) values (?,"
              + " 'test-refresh', DATE_ADD(NOW(),INTERVAL 1 DAY))",
          user);
      var scope = service.preview(user);
      assertThat(scope.users()).isEqualTo(44);
      assertThat(scope.schools()).isEqualTo(1);
      assertThatThrownBy(() -> service.preview(541004L)).isInstanceOf(SecurityException.class);
      assertThatThrownBy(() -> service.preview(541000L)).isInstanceOf(SecurityException.class);
      assertThatThrownBy(() -> service.reset(user, scope, "wrong", grant(user)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> service.reset(user, scope, PIN, "stale"))
          .isInstanceOf(PasskeyProblem.class);
      assertThat(service.preview(user)).isEqualTo(scope);
      String key = round.prefix() + "EMAIL_AUTH:fresh@example.invalid";
      redis.opsForValue().set(key, "111111");
      if (i == 0) {
        db.execute(
            "create trigger test_reset_rollback before delete on school for each row signal"
                + " sqlstate '45000' set message_text='test rollback'");
        try {
          assertThatThrownBy(() -> service.reset(user, scope, PIN, grant(user)))
              .isInstanceOf(org.springframework.dao.DataAccessException.class);
          assertThat(service.preview(user)).isEqualTo(scope);
          assertThat(db.queryForObject("select count(*) from user_refresh_tokens", Long.class))
              .isOne();
        } finally {
          db.execute("drop trigger test_reset_rollback");
        }
      }
      String used = grant(user);
      assertThat(service.reset(user, scope, PIN, used)).isEqualTo(scope);
      assertThatThrownBy(() -> service.reset(user, scope, PIN, used))
          .isInstanceOf(SecurityException.class);
      for (String table :
          new String[] {
            "users",
            "school",
            "school_user",
            "passkey_account",
            "user_refresh_tokens",
            "timetable_set"
          })
        assertThat(db.queryForObject("select count(*) from " + table, Long.class))
            .as(table)
            .isZero();
      assertThat(round.revision()).isEqualTo(scope.revision() + 1);
      assertThat(email.verifyCode("fresh@example.invalid", "111111")).isFalse();
    }
  }
}
