package com.rssolplan.edu.domain.demoreset;

import com.rssolplan.edu.domain.auth.passkey.PasskeyService;
import com.rssolplan.edu.domain.auth.passkey.PasskeyStore;
import com.rssolplan.edu.domain.schedule.candidates.School42Round;
import com.rssolplan.edu.domain.schedule.candidates.School42Round.TimetableScope;
import java.util.List;
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
  private final School42Round rehearsal;

  @Value("${demo.reset.operator:}")
  private String operator;

  @Value("${demo.reset.database:}")
  private String database;

  @Value("${demo.reset.pin-hash:}")
  private String pinHash;

  private static final DefaultRedisScript<Long> ATTEMPTS =
      new DefaultRedisScript<>(
          "local n=redis.call('incr',KEYS[1]); if n==1 then redis.call('expire',KEYS[1],300) end;"
              + " return n",
          Long.class);

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
      throw new SecurityException("지정된 시연 관리자만 School42 시간표를 초기화할 수 있습니다.");
    }
  }

  public School42Round.Setup setupStatus(Long user) {
    authorize(user);
    return rehearsal.setupStatus();
  }

  public School42Round.Setup prepare(Long user) {
    authorize(user);
    rehearsal.prepareTable();
    return rehearsal.prepare();
  }

  @Transactional(readOnly = true)
  public TimetableScope preview(Long user) {
    authorize(user);
    return rehearsal.timetableScope();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public TimetableScope reset(
      Long user, TimetableScope expected, String password, String approval) {
    authorize(user);
    passkeys.approveDemoReset(user, approval);
    Long attempts =
        redis.execute(ATTEMPTS, List.of("demo-reset:attempts:" + database + ":" + user));
    if (attempts == null || attempts > 5)
      throw new IllegalStateException("초기화 시도 횟수를 초과했습니다. 5분 뒤 다시 시도하세요.");
    if (!BCrypt.checkpw(password, pinHash))
      throw new IllegalArgumentException("초기화 비밀번호가 일치하지 않습니다.");
    var after = rehearsal.resetTimetables(expected);
    log.info(
        "School42 timetable reset by user {}: round {} to {}, {} jobs, {} versions",
        user,
        expected.round(),
        after.round(),
        expected.jobs(),
        expected.versions());
    return after;
  }
}
