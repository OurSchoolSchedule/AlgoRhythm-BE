package com.rssolplan.edu.domain.auth.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.rssolplan.edu.domain.auth.email.ResendEmailSender;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Runs the actual built frontend against the API; only email delivery is replaced. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.passkeys.environment=preview", "server.address=127.0.0.1"})
@PasskeyTestSchema
@EnabledIfEnvironmentVariable(named = "BE_A2_UI_TEST", matches = "1")
class PasskeyEntryBrowserTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @MockitoBean ResendEmailSender mail;
    @TempDir Path temp;

    @Test
    void lostDeviceCanEnrollThroughEmailWithoutRecoveryCode() throws Exception {
        assertThat(System.getenv("PASSKEY_FRONTEND_DIST")).as("Build the frontend first").isNotBlank();
        long school = java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        String schoolCode = "UI-" + school;
        String email = "ui-" + UUID.randomUUID() + "@ewha.ac.kr";
        db.update("INSERT INTO school(id,school_code,name) VALUES(?,?,?)", school, schoolCode, "패스키 재발급 학교");
        db.update("INSERT INTO school_teacher_roster(school_id,auth_email,display_name) VALUES(?,?,?)", school, email, "명부 교사");
        Path mailbox = temp.resolve("mail");
        when(mail.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var matcher = Pattern.compile(">([0-9]{6})<").matcher(call.<String>getArgument(2));
            assertThat(matcher.find()).isTrue();
            Files.writeString(mailbox, matcher.group(1));
            return "test-mail";
        });
        var process = new ProcessBuilder("node", "src/test/browser/passkey-entry.cjs",
                String.valueOf(port), mailbox.toString(), email, String.valueOf(school), schoolCode)
                .redirectErrorStream(true).redirectOutput(temp.resolve("browser.log").toFile()).start();
        try {
            assertThat(process.waitFor(150, TimeUnit.SECONDS)).as("UI browser test timeout").isTrue();
            assertThat(process.exitValue()).withFailMessage(() -> {
                try { return Files.readString(temp.resolve("browser.log")); }
                catch (Exception e) { return "UI browser check failed"; }
            }).isZero();
        } finally {
            process.destroyForcibly();
        }
    }
}
