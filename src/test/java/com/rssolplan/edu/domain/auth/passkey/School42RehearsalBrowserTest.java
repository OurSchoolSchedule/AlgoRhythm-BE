package com.rssolplan.edu.domain.auth.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.rssolplan.edu.domain.auth.email.ResendEmailSender;
import com.rssolplan.edu.domain.schedule.candidates.School42Round;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.passkeys.environment=preview", "server.address=127.0.0.1",
        "demo.reset.operator=eceun@ewha.ac.kr", "demo.reset.database=demo_rehearsal", "openai.api-key=",
        "email.resend.from=test@example.invalid","email.resend.api-key=isolated-mock-transport"})
@PasskeyTestSchema
@EnabledIfEnvironmentVariable(named = "SCHOOL42_REHEARSAL_TEST", matches = "1")
class School42RehearsalBrowserTest {
    private static final String OPERATOR = "eceun@ewha.ac.kr", ADMIN = "zieyou52@ewha.ac.kr",
            TEACHER1 = "reslc0307@gmail.com", TEACHER2 = "aaaalgorythm@gmail.com";
    private static final String PIN = UUID.randomUUID().toString().substring(0, 8);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        String hash = BCrypt.hashpw(PIN, BCrypt.gensalt(4));
        r.add("demo.reset.pin-hash", () -> hash);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired School42Round fixture;
    @MockitoBean ResendEmailSender mail;
    @TempDir Path temp;

    @Test void rehearseWithFourRealSessionsThreeTimes() throws Exception {
        assertThat(db.queryForObject("select database()", String.class)).isEqualTo("demo_rehearsal");
        assertThat(db.queryForObject("select count(*) from users", Long.class)).isZero();
        fixture.prepareTable();
        var start = fixture.prepare();
        db.update("insert into school(id,name) values(740002,'운영자 별도 학교')");
        db.update("""
                insert into timetable_set(id,school_id,academic_year,semester,start_date,end_date)
                values(740003,740002,2026,2,'2026-11-23','2026-12-04'),(740004,540001,2027,1,'2027-03-01','2027-07-31')
                """);
        db.update("""
                insert into schedule_version(term_id,version_no,effective_from)
                values (740003,1,'2026-11-23'),(740004,1,'2027-03-01')
                """);
        when(mail.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String address = call.getArgument(0);
            assertThat(Set.of(OPERATOR, ADMIN, TEACHER1, TEACHER2)).contains(address);
            var code = Pattern.compile(">([0-9]{6})<").matcher(call.<String>getArgument(2));
            assertThat(code.find()).isTrue();
            Files.writeString(temp.resolve(address), code.group(1));
            return "isolated-mailbox";
        });
        var log = temp.resolve("browser.log");
        var process = new ProcessBuilder("node", "src/test/browser/school42-rehearsal.cjs",
                String.valueOf(port), temp.toString(), PIN)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(160);
            while (process.isAlive() && !Files.exists(temp.resolve("accounts-ready")) && System.nanoTime() < until)
                Thread.sleep(100);
            assertThat(Files.exists(temp.resolve("accounts-ready"))).withFailMessage(() -> read(log)).isTrue();
            for (var email : new String[]{OPERATOR, ADMIN}) {
                long school = email.equals(OPERATOR) ? 740002 : 540001;
                assertThat(db.update("""
                        insert into school_user(user_id,school_id,position,employment_status,created_at,updated_at)
                        select user_id,?,'ADMIN','HIRED',now(),now() from roster_verified_account where auth_email=?
                        """, school, email)).isOne();
                assertThat(db.update("""
                        update users set active_school_id=? where id=(select user_id from roster_verified_account where auth_email=?)
                        """, school,email)).isOne();
            }
            Files.writeString(temp.resolve("roles-ready"), start.schoolCode());
            assertThat(process.waitFor(480, TimeUnit.SECONDS)).as("three rounds timed out").isTrue();
            assertThat(process.exitValue()).withFailMessage(() -> read(log)).isZero();
            assertThat(db.queryForObject("select count(*) from school42_reset_audit where reset_mode='FULL'", Long.class)).isEqualTo(3);
            assertThat(fixture.timetableScope().round()).isEqualTo(start.scope().round()+3);
            assertThat(fixture.timetableScope().versions()).isZero();
            assertThat(db.queryForObject("select count(*) from schedule_version where term_id in (740003,740004)", Long.class)).isEqualTo(2);
            assertThat(db.queryForObject("select count(*) from school_user where id between 542001 and 542042 and school_id=540001", Long.class)).isEqualTo(42);
            assertThat(db.queryForObject("select count(*) from passkey_credential",Long.class)).isEqualTo(4);
            System.out.print(read(log));
        } finally { process.destroyForcibly(); }
    }

    private static String read(Path log) {
        try { return Files.readString(log); } catch (Exception e) { return "Browser log unavailable"; }
    }
}
