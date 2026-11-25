package com.rssolplan.edu.domain.auth.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import com.rssolplan.edu.domain.auth.email.ResendEmailSender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.passkeys.environment=preview", "server.address=127.0.0.1"})
@PasskeyTestSchema
@EnabledIfEnvironmentVariable(named = "BE_A2_BROWSER_TEST", matches = "1")
class PasskeyBrowserTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @MockitoBean ResendEmailSender mail;
    @TempDir Path temp;

    @Test
    void browserRegistrationAuthenticationDeviceManagementAndRecovery() throws Exception {
        long school =
                java.util.concurrent.ThreadLocalRandom.current()
                        .nextLong(1_000_000_000L, 2_000_000_000L);
        String schoolCode = "A2-" + school;
        String email = "a2-" + java.util.UUID.randomUUID() + "@ewha.ac.kr";
        db.update(
                "INSERT INTO school(id,school_code,name) VALUES(?,?,?)",
                school,
                schoolCode,
                "패스키 학교");
        db.update(
                "INSERT INTO school_teacher_roster(school_id,auth_email,display_name)"
                    + " VALUES(?,?,?)",
                school,
                email,
                "명부 교사");
        Path mailbox = temp.resolve("mail");
        when(mail.send(anyString(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            var matcher =
                                    Pattern.compile(">([0-9]{6})<")
                                            .matcher(call.<String>getArgument(2));
                            assertThat(matcher.find()).isTrue();
                            Files.writeString(mailbox, matcher.group(1));
                            return "test-mail";
                        });
        var process =
                new ProcessBuilder(
                                "node",
                                "src/test/browser/passkey.cjs",
                                String.valueOf(port),
                                mailbox.toString(),
                                email,
                                String.valueOf(school),
                                schoolCode)
                        .redirectErrorStream(true)
                        .redirectOutput(temp.resolve("browser.log").toFile())
                        .start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("Browser test timeout").isTrue();
            assertThat(process.exitValue())
                    .withFailMessage(
                            () -> {
                                try {
                                    return Files.readString(temp.resolve("browser.log"));
                                } catch (Exception e) {
                                    return "Browser check failed";
                                }
                            })
                    .isZero();
        } finally {
            process.destroyForcibly();
        }
    }
}
