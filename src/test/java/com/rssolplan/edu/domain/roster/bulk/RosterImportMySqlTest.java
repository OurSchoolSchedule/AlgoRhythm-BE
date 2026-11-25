package com.rssolplan.edu.domain.roster.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import com.rssolplan.edu.domain.roster.TeacherRosterService;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.dev-login-enabled=true", "app.passkeys.environment=local",
        "spring.jpa.defer-datasource-initialization=true", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V20261122_01__school_load_and_schedule_versions.sql," +
                "classpath:db/migration/V20261124_01__generation_and_lesson_operations.sql," +
                "classpath:db/migration/V20261125_11__school_teacher_roster.sql," +
                "classpath:db/migration/V20261125_21__passkey_auth.sql," +
                "classpath:db/migration/V20261125_22__roster_import_preview.sql"})
@EnabledIfEnvironmentVariable(named = "R2_MYSQL_TEST", matches = "1")
class RosterImportMySqlTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired JwtTokenProvider jwt;
    @MockitoSpyBean TeacherRosterService roster;
    // Detached fixture IDs keep repeated runs independent on a persistent local test database.
    final long school = ThreadLocalRandom.current().nextLong(100_000_000_000L, 900_000_000_000L);
    final long other = school + 1000, term = school, admin = school;
    final String a = "one@example.invalid", b = "two@example.invalid";

    @BeforeEach
    void fixture() {
        for (long id : List.of(school, other)) {
            db.update("insert into school(id,name) values(?,'R2 학교')", id);
            member(id, id, "ADMIN");
            db.update("insert into timetable_set(id,school_id,academic_year,semester,start_date,end_date) " +
                    "values(?,?,2026,2,'2026-08-01','2026-12-31')", id, id);
            for (int i = 1; i <= 2; i++) {
                db.update("insert into subject(id,school_id,name) values(?,?,?)", id + i, id, "과목" + i);
                db.update("insert into school_class(id,school_id,academic_year,grade,class_number) values(?,?,2026,1,?)", id + i, id, i);
            }
        }
    }

    void member(long user, long schoolId, String role) {
        db.update("insert into users(id,active_school_id,username) values(?,?,'R2 사용자')", user, schoolId);
        db.update("insert into school_user(id,school_id,user_id,position,employment_status) values(?,?,?,?,'HIRED')",
                user, schoolId, user, role);
    }

    String base(long site, long semester) { return "/api/schools/" + site + "/roster/terms/" + semester + "/imports"; }
    HttpHeaders headers(long user) {
        var h = new HttpHeaders();
        h.setBearerAuth(jwt.generateAccess(user));
        return h;
    }
    JsonNode request(HttpMethod method, String path, long user, Object data, int status) {
        var response = http.exchange(path, method, new HttpEntity<>(data, headers(user)), JsonNode.class);
        assertThat(response.getStatusCode().value()).as("%s %s: %s", method, path, response.getBody()).isEqualTo(status);
        return response.getBody();
    }

    JsonNode preview(long site, long semester, long user, byte[] file, int status) {
        var h = headers(user);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(file) {
            @Override public String getFilename() { return "교사명부.xlsx"; }
        });
        var response = http.postForEntity(base(site, semester) + "/preview", new HttpEntity<>(form, h), JsonNode.class);
        assertThat(response.getStatusCode().value()).as("preview: %s", response.getBody()).isEqualTo(status);
        return response.getBody();
    }
    JsonNode preview(byte[] file, int expected) { return preview(school, term, admin, file, expected); }
    JsonNode confirm(JsonNode preview, int status) {
        return confirm(admin, school, term, preview.path("previewId").asText(), preview.path("fileSha256").asText(), status);
    }
    JsonNode confirm(long user, long site, long semester, String preview, String hash, int status) {
        return request(HttpMethod.POST, base(site, semester) + "/" + preview + "/confirm", user, Map.of("fileSha256", hash), status);
    }

    static byte[] workbook(String[]... data) throws IOException {
        try (var w = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = w.createSheet("교사명부");
            Row header = sheet.createRow(0);
            for (int i = 0; i < RosterWorkbook.HEADER.size(); i++) header.createCell(i).setCellValue(RosterWorkbook.HEADER.get(i));
            for (int i = 0; i < data.length; i++) {
                Row row = sheet.createRow(i + 1);
                for (int c = 0; c < data[i].length; c++) row.createCell(c).setCellValue(data[i][c]);
            }
            w.write(out);
            return out.toByteArray();
        }
    }
    String[] row(String email, String name, long subject, long room) {
        return new String[]{email, name, subject == 0 ? "" : Long.toString(subject), room == 0 ? "" : Long.toString(room)};
    }
    int count(String sql, Object... args) { return db.queryForObject(sql, Integer.class, args); }
    int teachers() { return count("select count(*) from school_teacher_roster where school_id=?", school); }

    @Test
    void importingAnExplicitlyEmptyAssignmentCreatesAMissingTermOnlyOnce() throws Exception {
        roster.register(admin, school, new TeacherRosterService.IdentityInput(a, "기존 교사"));
        byte[] file = workbook(row(a, "기존 교사", 0, 0));
        var first = preview(file, 200);
        assertThat(first.at("/counts/update").asInt()).isEqualTo(1);
        assertThat(first.at("/changes/0/before/term/assignmentRecorded").booleanValue()).isFalse();
        confirm(first, 200);
        assertThat(roster.list(admin, school, term).get(0).term().assignmentRecorded()).isTrue();
        assertThat(preview(file, 200).at("/counts/unchanged").asInt()).isEqualTo(1);
    }

    @Test
    void templateContainsOnlySelectedSchoolAndYearAndRejectsTimeLoadWorkbook() throws Exception {
        var response = http.exchange(base(school, term) + "/template", HttpMethod.GET, new HttpEntity<>(headers(admin)), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (var w = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            assertThat(w.getSheetAt(0).getSheetName()).isEqualTo("교사명부");
            assertThat(w.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("인증 이메일");
            assertThat(w.getSheet("코드표").getLastRowNum()).isEqualTo(4);
            assertThat(w.getSheet("코드표").getRow(1).getCell(1).getStringCellValue()).isEqualTo("" + (school + 1));
        }
        assertThat(preview("csv".getBytes(java.nio.charset.StandardCharsets.UTF_8), 415).path("code").asText())
                .isEqualTo("XLSX_REQUIRED");
        assertThat(preview(response.getBody(), 422).at("/errors/0/code").asText()).isEqualTo("EMPTY_ROSTER");
        assertThat(preview(Files.readAllBytes(Path.of("demo/valid.xlsx")), 422).path("code").asText()).isEqualTo("SELECT_SHEET");
    }

    @Test
    void cellErrorsShowRowColumnAndReasonAndNeverOfferConfirmForPartiallyValidInput() throws Exception {
        var file = workbook(row(a, "김교사", school + 1, school + 1),
                row(a, "상충 이름", school + 1, school + 2), row(b, "김교사", other + 1, other + 1),
                row("broken", "", 0, 0), row("valid@example.invalid", "정상", school + 2, 0));
        var result = preview(file, 422);
        assertThat(result.required("previewId").isNull()).isTrue();
        assertThat(result.at("/counts/add").asInt()).isEqualTo(1);
        var cells = new HashMap<String, String>();
        result.required("errors").forEach(e -> {
            assertThat(e.path("cell").asText()).isEqualTo(e.path("column").asText() + e.path("row").asInt());
            assertThat(e.path("message").asText()).isNotBlank();
            cells.put(e.path("cell").asText(), e.path("code").asText());
        });
        assertThat(cells).containsEntry("B3", "CONFLICTING_NAME").containsEntry("C3", "DUPLICATE_SUBJECT")
                .containsEntry("D3", "CONFLICTING_HOMEROOM").containsEntry("C4", "UNKNOWN_SUBJECT")
                .containsEntry("D4", "UNKNOWN_CLASS").containsEntry("A5", "INVALID_EMAIL");
        assertThat(teachers()).isZero();
        assertThat(count("select count(*) from roster_import_preview where school_id=?", school)).isZero();
        confirm(admin, school, term, UUID.randomUUID().toString(), result.path("fileSha256").asText(), 404);
    }

    @Test
    void confirmsSwappedHomeroomsTogetherPreservesOmittedTeacherAndDoesNotCreateAccountOrApprove() throws Exception {
        String third = "omit@example.invalid";
        var first = preview(workbook(row(a, "동명이인", school + 1, school + 1), row(b, "동명이인", 0, school + 2),
                row(third, "유지", school + 2, 0)), 200);
        confirm(first, 200);
        long pending = school + 13;
        db.update("insert into users(id,username) values(?,'신청자')", pending);
        db.update("update school_teacher_roster set pending_user_id=? where school_id=? and auth_email=?", pending, school, a);
        long linked = school + 14;
        member(linked, school, "TEACHER");
        db.update("update school_teacher_roster set school_user_id=? where school_id=? and auth_email=?", linked, school, b);
        var second = preview(workbook(row(a, "이름 변경", school + 1, school + 2), row(a, "이름 변경", school + 2, 0),
                row(b, "동명이인", 0, school + 1)), 200);
        assertThat(second.at("/counts/update").asInt()).isEqualTo(2);
        assertThat(second.at("/changes/0/before/status").asText()).isEqualTo("PENDING");
        confirm(second, 200);
        var entries = roster.list(admin, school, term);
        assertThat(entries).hasSize(3);
        var one = entries.stream().filter(e -> e.authEmail().equals(a)).findFirst().orElseThrow();
        assertThat(one.status()).isEqualTo("PENDING");
        assertThat(one.term().subjects()).hasSize(2);
        assertThat(one.term().homeroomClass().classId()).isEqualTo(school + 2);
        assertThat(entries.stream().filter(e -> e.authEmail().equals(third)).findFirst().orElseThrow().term().subjects()).hasSize(1);
        assertThat(count("select count(*) from school_user where school_id=?", school)).isEqualTo(2);
        assertThat(entries.stream().filter(e -> e.authEmail().equals(b)).findFirst().orElseThrow().schoolUserId()).isEqualTo(linked);
        assertThat(db.queryForObject("select username from users where id=?", String.class, linked)).isEqualTo("R2 사용자");
        assertThat(preview(workbook(row(a, "이름 변경", school + 1, school + 1)), 422).at("/errors/0/code").asText())
                .isEqualTo("HOMEROOM_OCCUPIED");
    }

    private static List<JsonNode> race(Callable<JsonNode> work) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        Callable<JsonNode> request = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return work.call();
        };
        try {
            var one = pool.submit(request);
            var two = pool.submit(request);
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void concurrentUploadAndConfirmationReplayOneReceiptAndChangedFileRequiresAnotherPreview() throws Exception {
        var file = workbook(row(a, "김교사", school + 1, 0));
        var previews = race(() -> preview(file, 200));
        assertThat(previews.get(1)).isEqualTo(previews.get(0));
        var p = previews.get(0);
        var changed = preview(workbook(row(a, "다른 이름", school + 1, 0)), 200);
        assertThat(confirm(admin, school, term, p.path("previewId").asText(), changed.path("fileSha256").asText(), 409)
                .path("error").asText()).isEqualTo("FILE_CHANGED");
        assertThat(teachers()).isZero();
        var receipts = race(() -> confirm(p, 200));
        assertThat(receipts.get(1)).isEqualTo(receipts.get(0));
        assertThat(confirm(p, 200)).isEqualTo(receipts.get(0));
        assertThat(teachers()).isEqualTo(1);
        assertThat(preview(file, 200).at("/counts/unchanged").asInt()).isEqualTo(1);
        assertThat(confirm(changed, 409).path("error").asText()).isEqualTo("STALE_ROSTER");
    }

    @Test
    void confirmRechecksCatalogOriginalRosterIndependentTermAdminAndEmployment() throws Exception {
        var file = workbook(row(a, "교사", school + 1, 0));
        preview(other, other, admin, file, 403);
        preview(school, other, admin, file, 404);
        var p = preview(file, 200);
        String id = p.path("previewId").asText(), hash = p.path("fileSha256").asText();
        member(school + 20, school, "ADMIN");
        member(school + 21, school, "TEACHER");
        confirm(school + 20, school, term, id, hash, 404);
        confirm(other, other, other, id, hash, 404);
        confirm(admin, school, other, id, hash, 404);
        preview(school, term, school + 21, file, 403);
        db.update("update school_user set employment_status='ON_LEAVE' where id=?", admin);
        confirm(admin, school, term, id, hash, 403);
        db.update("update school_user set employment_status='HIRED' where id=?", admin);
        db.update("update subject set name='바뀐 과목' where id=?", school + 1);
        assertThat(confirm(p, 409).path("error").asText()).isEqualTo("STALE_ROSTER");
        assertThat(teachers()).isZero();
        var fresh = preview(file, 200);
        db.update("update roster_import_preview set created_at=current_timestamp - interval 2 day where id=?",
                fresh.path("previewId").asText());
        assertThat(confirm(fresh, 409).path("error").asText()).isEqualTo("PREVIEW_EXPIRED");
    }

    @Test
    void individualRosterEditAfterPreviewWinsAndOtherTermAssignmentsSurvive() throws Exception {
        var first = preview(workbook(row(a, "기존", school + 1, 0)), 200);
        confirm(first, 200);
        var entry = roster.list(admin, school, term).get(0);
        long spring = school + 44;
        db.update("insert into timetable_set(id,school_id,academic_year,semester,start_date,end_date) " +
                "values(?,?,2026,1,'2026-03-01','2026-07-31')", spring, school);
        roster.replaceAssignment(admin, school, entry.rosterId(), spring,
                new TeacherRosterService.AssignmentInput(List.of(school + 2), school + 1));
        var replacement = workbook(row(a, "일괄 변경", school + 2, 0), row(b, "신규", 0, 0));
        var pending = preview(replacement, 200);
        String detail = "/api/schools/" + school + "/roster/" + entry.rosterId();
        request(HttpMethod.PUT, detail, admin, Map.of("authEmail", a, "displayName", "개별 수정"), 200);
        assertThat(confirm(pending, 409).path("error").asText()).isEqualTo("STALE_ROSTER");
        assertThat(teachers()).isOne();
        assertThat(request(HttpMethod.GET, detail, admin, null, 200).path("displayName").asText()).isEqualTo("개별 수정");
        confirm(preview(replacement, 200), 200);
        var previous = request(HttpMethod.GET, detail + "?termId=" + spring, admin, null, 200);
        assertThat(previous.at("/term/subjects/0/subjectId").asLong()).isEqualTo(school + 2);
        assertThat(previous.at("/term/homeroomClass/classId").asLong()).isEqualTo(school + 1);
    }

    @Test
    void ambiguousUnassignedFormulasAndWrongAcademicYearReportTheirCells() throws Exception {
        db.update("insert into school_class(id,school_id,academic_year,grade,class_number) values(?,?,2025,1,1)",
                school + 7, school);
        var raw = workbook(row(a, "과목 없음", 0, 0), row(a, "과목 없음", school + 1, 0),
                row(b, "잘못된 학급", school + 1, school + 7));
        try (var w = new XSSFWorkbook(new ByteArrayInputStream(raw)); var out = new ByteArrayOutputStream()) {
            var row = w.getSheetAt(0).createRow(4);
            row.createCell(0).setCellValue("formula@example.invalid");
            row.createCell(1).setCellFormula("1+1");
            w.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(row.getCell(1));
            w.write(out);
            var errors = preview(out.toByteArray(), 422).required("errors");
            var codes = new HashMap<String, String>();
            errors.forEach(e -> codes.put(e.path("code").asText(), e.path("cell").asText()));
            assertThat(codes).containsEntry("AMBIGUOUS_UNASSIGNED", "C2").containsEntry("UNKNOWN_CLASS", "D4")
                    .containsEntry("INVALID_CELL", "B5");
        }
        assertThat(teachers()).isZero();
    }

    @Test
    void failureInSecondWriteRollsBackFirstTeacherAndReceipt() throws Exception {
        var p = preview(workbook(row(a, "먼저 반영", school + 1, 0), row(b, "실패 사례", school + 2, 0)), 200);
        doThrow(new IllegalStateException("test write failure")).when(roster)
                .register(admin, school, new TeacherRosterService.IdentityInput(b, "실패 사례"));
        confirm(p, 409);
        assertThat(teachers()).isZero();
        assertThat(count("select count(*) from roster_import_preview where school_id=? and confirmed_at is not null", school)).isZero();
    }

    @Test
    void actualSchool42WorkbookAppliedToAnIsolatedCopyOfItsCatalog() throws Exception {
        // Preserve the sample's email/name/assignments; only translate its catalog PKs to the
        // isolated test school's PKs so repeated or concurrent suites never claim a real ID.
        db.update("delete from school_class where school_id=?", school);
        var sample = Files.readAllBytes(Path.of("demo/school42-roster.xlsx"));
        byte[] file;
        try (var w = new XSSFWorkbook(new ByteArrayInputStream(sample)); var out = new ByteArrayOutputStream()) {
            var codes = w.getSheet("코드표");
            for (int i = 1; i <= codes.getLastRowNum(); i++) {
                var r = codes.getRow(i); long original = Long.parseLong(r.getCell(1).getStringCellValue());
                if (r.getCell(0).getStringCellValue().equals("과목")) {
                    db.update("insert into subject(id,school_id,name) values(?,?,?)", school + original, school, r.getCell(2).getStringCellValue());
                } else {
                    int seq = (int)(original - 544001);
                    db.update("insert into school_class(id,school_id,academic_year,grade,class_number) values(?,?,2026,?,?)",
                            school + original, school, 1 + seq / 7, 1 + seq % 7);
                }
            }
            var sheet = w.getSheetAt(0);
            for (int row = 1; row <= sheet.getLastRowNum(); row++) {
                for (int col = 2; col <= 3; col++) {
                    var cell = sheet.getRow(row).getCell(col);
                    if (cell != null && !cell.getStringCellValue().isBlank()) {
                        String translated = Long.toString(school + Long.parseLong(cell.getStringCellValue()));
                        sheet.getRow(row).removeCell(cell);
                        sheet.getRow(row).createCell(col).setCellValue(translated);
                    }
                }
            }
            w.write(out);
            file = out.toByteArray();
        }
        var p = preview(file, 200);
        assertThat(p.at("/counts/add").asInt()).isEqualTo(42);
        confirm(p, 200);
        var entries = roster.list(admin, school, term);
        assertThat(entries).hasSize(42);
        assertThat(entries.stream().map(e -> e.authEmail()).distinct().count()).isEqualTo(42);
        assertThat(entries.stream().filter(e -> e.displayName().equals("김서윤")).count()).isEqualTo(2);
        assertThat(entries.stream().filter(e -> e.term().subjects().size() > 1).count()).isEqualTo(41);
        assertThat(entries.stream().filter(e -> e.term().subjects().isEmpty()).count()).isEqualTo(1);
        assertThat(entries.stream().mapToInt(e -> e.term().subjects().size()).sum()).isEqualTo(142);
        assertThat(entries.stream().filter(e -> e.term().homeroomClass() != null).count()).isEqualTo(21);
        assertThat(preview(file, 200).at("/counts/unchanged").asInt()).isEqualTo(42);
    }
}
