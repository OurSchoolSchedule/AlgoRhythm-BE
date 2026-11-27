package com.rssolplan.edu.domain.roster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.auth.email.ResendEmailSender;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Isolated fixtures; run against a disposable MySQL database and Redis. Only outbound mail is stubbed. */
@SpringBootTest(properties = "app.auth.dev-login-enabled=true")
@org.springframework.test.context.ActiveProfiles("local")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "R1_MYSQL_TEST", matches = "1")
class TeacherRosterMySqlTest {
    static final String R1 = "db/migration/V20261125_11__school_teacher_roster.sql";
    static final String VERIFY = "/api/auth/email-verification/verify";
    static final String ONBOARD = "/api/auth/onboarding";
    static final String ADD_SCHOOL = "/api/mypage/teacher/schools";

    @Autowired DataSource source;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Autowired StringRedisTemplate redis;
    @MockitoBean ResendEmailSender mail;

    // Explicit IDs below are unique within each table. Real signup still uses generated IDs.
    final long a = ThreadLocalRandom.current().nextLong(10_000_000_000L, 80_000_000_000L);
    final long b = a + 20;
    final String email = "faculty" + a + "@gmail.com";

    @BeforeEach
    void fixtures() {
        for (long school : List.of(a, b)) {
            db.update("insert into school(id,school_code,name) values(?,?,?)", school, code(school), "School " + school);
            db.update("insert into users(id,username,active_school_id) values(?,'admin',?)", school, school);
            db.update("insert into school_user(id,user_id,school_id,position,employment_status) values(?,?,?,'ADMIN','HIRED')",
                    school, school, school);
            db.update("insert into subject(id,school_id,name) values(?,?,'수학'),(?,?,'과학')",
                    school, school, school + 1, school);
            db.update("insert into school_class(id,school_id,academic_year,grade,class_number) values(?,?,2026,1,1),(?,?,2025,1,1)",
                    school, school, school + 1, school);
            db.update("insert into timetable_set(id,school_id,academic_year,semester,start_date,end_date) values"
                            + "(?,?,2026,1,'2026-03-01','2026-07-31'),(?,?,2026,2,'2026-08-01','2026-12-31')",
                    school, school, school + 1, school);
        }
        when(mail.send(anyString(), anyString(), anyString())).thenReturn("fixture-message");
    }

    @BeforeAll
    static void migrate(@Autowired DataSource source) {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261122_01__school_load_and_schedule_versions.sql"),
                new ClassPathResource("db/migration/V20261124_01__generation_and_lesson_operations.sql"),
                new ClassPathResource(R1),
                new ClassPathResource("db/migration/V20261126_31__school_roster_names.sql"),
                new ClassPathResource("db/migration/V20261127_41__roster_teaching_link.sql"),
                new ClassPathResource("db/migration/V20261125_21__passkey_auth.sql")).execute(source);
    }

    String code(long school) { return "R1-" + school; }
    String base(long school) { return "/api/schools/" + school + "/roster"; }
    String admin(long school) { return jwt.generateAccess(school); }

    JsonNode http(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        String result = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).withFailMessage("Expected %d, got %d: %s", expected, response.getStatus(), result)
                .isEqualTo(expected);
        return result.isBlank() ? json.nullNode() : json.readTree(result);
    }

    // Roster authorization cases start with an explicit local fixture session. The browser test
    // covers the real OTP -> WebAuthn -> protected roster route, including the intervening denial.
    String localSessionFor(JsonNode verified) {
        assertThat(verified.hasNonNull("accessToken")).isFalse();
        assertThat(verified.required("flowToken").asText()).isNotBlank();
        return jwt.generateAccess(verified.required("userId").asLong());
    }

    long create(long school, String address, String name) throws Exception {
        var row = http(post(base(school)), admin(school), Map.of("authEmail", address, "displayName", name), 201);
        assertThat(row.path("status").asText()).isEqualTo("UNLINKED");
        assertThat(row.get("schoolUserId").isNull()).isTrue();
        return row.required("rosterId").longValue();
    }

    String sendCode(String address) throws Exception {
        http(post("/api/auth/email-verification/send"), null, Map.of("email", address), 200);
        String otp = redis.opsForValue().get("EMAIL_AUTH:" + address);
        assertThat(otp).matches("[0-9]{6}");
        return otp;
    }

    JsonNode verify(String address, Long school) throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put("email", address);
        input.put("code", sendCode(address));
        if (school != null) input.put("schoolCode", code(school));
        return http(post(VERIFY), null, input, 200);
    }

    Map<String, Object> onboarding(long school) {
        return Map.of("role", "TEACHER", "schoolCode", code(school), "displayName", "신청 교사");
    }

    long members(long user, long school) {
        return db.queryForObject("select count(*) from school_user where user_id=? and school_id=?", Long.class, user, school);
    }

    long rosterCount(long school, String address) {
        return db.queryForObject("select count(*) from school_teacher_roster where school_id=? and auth_email=?",
                Long.class, school, address);
    }

    <T> List<T> race(Callable<T> request) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        Callable<T> contender = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return request.call();
        };
        try {
            var first = pool.submit(contender);
            var second = pool.submit(contender);
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void adminBindsVerifiedApplicantToExistingTeachingIdentityWithoutChangingItsLessons() throws Exception {
        long school = 540001, term = 547001, dummy = 542004;
        db.update("insert into school(id,name,school_code) values(?, '시연한들고등학교',?)", school,code(school));
        db.update("insert into users(id,username,active_school_id) values(?,'시연 관리자',?)", a+8,school);
        db.update("insert into school_user(id,user_id,school_id,position,employment_status) values(?,?,?,'ADMIN','HIRED')",
                a+8,a+8,school);
        db.update("insert into users(id,username,email,active_school_id) values(541004,'최도현','teacher42-04@example.invalid',?)",school);
        db.update("insert into school_user(id,user_id,school_id,position,employment_status) values(?,541004,?,'TEACHER','HIRED')", dummy, school);
        db.update("insert into timetable_set(id,school_id,academic_year,semester,start_date,end_date) values(?,?,2026,2,'2026-11-23','2026-12-04')",term,school);
        db.update("insert into subject(id,school_id,name) values(?,?, '공통국어2')",a+8,school);
        db.update("insert into school_class(id,school_id,academic_year,grade,class_number) values(?,?,2026,1,1)",a+8,school);
        db.update("insert into load_import(id,term_id,version_no,created_by,normalized_sha,sheet_name) values(?,?,1,?,?,'검증')",
                a+8,term,a+8,"a".repeat(64));
        db.update("insert into load_demand(load_id,teacher_id,subject_id,class_id,weekly_periods,source_cell) values(?,?,?,?,4,'A2')",
                a+8,dummy,a+8,a+8);
        db.update("insert into school_setting(id,school_id,break_duration,period_duration) values(?,?,10,50)",a+8,school);
        db.update("insert into period_setting(id,school_setting_id,period_number,start_time,end_time) values(?,?,1,'08:40','09:30')",a+8,a+8);
        db.update("insert into schedule_version(id,term_id,load_id,version_no,effective_from) values(?,?,?,1,'2026-11-23')",a+8,term,a+8);
        db.update("insert into weekly_lesson(version_id,lesson_key,class_id,period_id,subject_id,teacher_id,weekday) values(?,'fixture',?,?,?,?,1)",
                a+8,a+8,a+8,a+8,dummy);
        String requester = localSessionFor(verify(email,null)), administrator = admin(a+8);
        String requestedName = "같은 과목 교사";
        var payload = Map.of("role","TEACHER","schoolCode",code(school),"displayName",requestedName,"subjectHint","공통국어2");
        http(post(ONBOARD),requester,payload,202);
        http(get("/api/school/teachers"),requester,null,403);
        long applicant = http(get(base(school)+"/me"),requester,null,200).required("rosterId").asLong();
        String path = base(school)+"/"+applicant;
        var options = http(get(path+"/match-options"),administrator,null,200);
        assertThat(options.required("applicantName").asText()).isEqualTo(requestedName);
        assertThat(options.required("verifiedEmail").asText()).isEqualTo(email);
        assertThat(options.required("requestedAt").isTextual()).isTrue();
        assertThat(options.required("subjectHint").asText()).isEqualTo("공통국어2");
        assertThat(options.at("/candidates/0/teacherSchoolUserId").asLong()).isEqualTo(dummy);
        assertThat(options.at("/candidates/0/weeklyPeriods").asInt()).isEqualTo(4);
        assertThat(options.at("/candidates/0/subjects/0").asText()).isEqualTo("공통국어2");
        http(get(path+"/match-options"),requester,null,403);
        http(get(path+"/match-options").param("termId",""+b),administrator,null,404);
        http(post(path+"/approve"),administrator,null,400);
        http(post(path+"/approve"),administrator,Map.of("teacherSchoolUserId",b),400);
        var bind = Map.of("teacherSchoolUserId",dummy);
        var approved = http(post(path+"/approve"),administrator,bind,200);
        assertThat(approved.required("teachingSchoolUserId").asLong()).isEqualTo(dummy);
        long accountMember = approved.required("schoolUserId").asLong();
        assertThat(accountMember).isNotEqualTo(dummy);
        assertThat(http(post(path+"/approve"),administrator,bind,200)).isEqualTo(approved);
        var teachers = http(get("/api/school/teachers"),requester,null,200);
        assertThat(teachers.size()).isOne();
        assertThat(teachers.at("/0/schoolUserId").asLong()).isEqualTo(dummy);
        assertThat(teachers.at("/0/username").asText()).isEqualTo("최도현");
        assertThat(teachers.at("/0/userId").asLong()).isEqualTo(db.queryForObject("SELECT user_id FROM school_user WHERE id=?",Long.class,accountMember));
        var personalWeek = http(get("/api/timetable/me").param("termId",""+term)
                .param("weekOf","2026-11-23"),requester,null,200);
        assertThat(personalWeek.size()).isOne();
        assertThat(personalWeek.at("/0/subjectName").asText()).isEqualTo("공통국어2");
        assertThat(personalWeek.at("/0/teacherId").asLong()).isEqualTo(dummy);
        assertThat(personalWeek.at("/0/teacherName").asText()).isEqualTo("최도현");
        // An already employed teacher from an older release may still need a teaching link.
        db.update("DELETE FROM roster_teaching_link WHERE roster_id=?", applicant);
        db.update("INSERT INTO teacher_availability(school_user_id,day_of_week,period_number) VALUES(?,'THU',2)", accountMember);
        http(post(path+"/approve"),administrator,bind,409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM roster_teaching_link WHERE roster_id=?",Long.class,applicant)).isZero();
        db.update("DELETE FROM teacher_availability WHERE school_user_id=?", accountMember);
        assertThat(http(get(path+"/match-options"),administrator,null,200).required("verifiedEmail").asText()).isEqualTo(email);
        assertThat(http(post(path+"/approve"),administrator,bind,200).required("schoolUserId").asLong()).isEqualTo(accountMember);
        String second = localSessionFor(verify("second"+email,null));
        http(post(ONBOARD),second,Map.of("role","TEACHER","schoolCode",code(school),"displayName","다른 신청자"),202);
        long secondId = http(get(base(school)+"/me"),second,null,200).required("rosterId").asLong();
        http(post(base(school)+"/"+secondId+"/approve"),administrator,bind,409);
        assertThat(http(get(base(school)+"/me"),second,null,200).required("status").asText()).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT teacher_id FROM load_demand WHERE load_id=?",Long.class,a+8)).isEqualTo(dummy);
    }

    @Test
    void bothDirectoriesDistinguishLegacyMissingTermAndExplicitAssignments() throws Exception {
        long teacher = a + 6;
        db.update("insert into users(id,username,active_school_id) values(?,'기존 교사',?)", teacher, a);
        db.update("insert into school_user(id,user_id,school_id,position,employment_status) values(?,?,?,'TEACHER','HIRED')",
                teacher, teacher, a);
        db.update("update school_class set homeroom_teacher_id=? where id=?", teacher, a);
        JsonNode legacy = directoryTeacher(teacher, a);
        assertThat(legacy.required("rosterLinked").booleanValue()).isFalse();
        assertThat(legacy.at("/homeroomClasses/0/classId").longValue()).isEqualTo(a);

        long id = create(a, email, "명부 교사");
        db.update("update school_teacher_roster set school_user_id=? where id=?", teacher, id);
        JsonNode absent = directoryTeacher(teacher, a);
        assertThat(absent.required("rosterLinked").booleanValue()).isTrue();
        assertThat(absent.required("rosterAssignmentTermId").isNull()).isTrue();
        assertThat(http(get(base(a) + "/me").param("termId", "" + a), admin(teacher), null, 200)
                .at("/term/assignmentRecorded").booleanValue()).isFalse();
        http(put(base(a) + "/" + id + "/terms/" + a), admin(a), Map.of("subjectIds", List.of()), 200);
        JsonNode empty = directoryTeacher(teacher, a);
        assertThat(empty.required("rosterAssignmentTermId").longValue()).isEqualTo(a);
        assertThat(empty.required("rosterHomeroomClass").isNull()).isTrue();
        http(put(base(a) + "/" + id + "/terms/" + (a + 1)), admin(a),
                Map.of("subjectIds", List.of(), "homeroomClassId", a), 200);
        JsonNode assigned = directoryTeacher(teacher, a + 1);
        assertThat(assigned.required("rosterAssignmentTermId").longValue()).isEqualTo(a + 1);
        assertThat(assigned.at("/rosterHomeroomClass/classId").longValue()).isEqualTo(a);
        assertThat(directoryTeacher(teacher, null).required("rosterAssignmentTermId").isNull()).isTrue();
        http(get("/api/school/teachers").param("termId", "" + b), admin(teacher), null, 404);
    }

    private JsonNode directoryTeacher(long teacher, Long termId) throws Exception {
        JsonNode reference = null;
        for (String path : List.of("/api/school/teachers", "/api/school/members")) {
            var request = get(path);
            if (termId != null) request.param("termId", termId.toString());
            var rows = http(request, admin(path.endsWith("/members") ? a : teacher), null, 200);
            var row = java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                    .filter(r -> r.required("schoolUserId").longValue() == teacher).findFirst().orElseThrow();
            if (reference != null) assertThat(row).isEqualTo(reference);
            reference = row;
        }
        return reference;
    }

    @Test
    void teachingDirectoryKeepsRosterIdentitiesAndUsesTheRepublishedLoad() throws Exception {
        long first = create(a, email, "김교사");
        long second = create(a, "peer" + email, "김교사");
        db.update("UPDATE school_teacher_roster SET school_user_id=? WHERE id=?", a, first);
        http(put(base(a) + "/" + first + "/terms/" + a), admin(a),
                Map.of("subjectIds", List.of(a + 1), "homeroomClassId", a), 200);
        String path = "/api/v2/terms/" + a + "/teaching-directory";
        var initial = http(get(path), admin(a), null, 200);
        assertThat(initial.get("loadId").isNull()).isTrue();
        assertThat(initial.at("/teachers/0/grades").size()).isZero();
        assertThat(initial.at("/teachers/1/rosterId").longValue()).isEqualTo(second);
        db.update("INSERT INTO school_class(id,school_id,academic_year,grade,class_number) VALUES(?,?,2026,3,1)",
                a + 2, a);
        for (int version : List.of(1, 2)) {
            long load = a + version;
            db.update("INSERT INTO load_import(id,term_id,version_no,created_by,normalized_sha,sheet_name)"
                    + " VALUES(?,?,?,?,?, 'test')", load, a, version, a, String.valueOf(version).repeat(64));
            db.update("INSERT INTO load_demand(load_id,teacher_id,subject_id,class_id,weekly_periods,source_cell)"
                    + " VALUES(?,?,?,?,1,'A2')", load, a, a, version == 1 ? a + 2 : a);
        }
        // Publication 3 reuses import 1. Import 2 and the homeroom both point to grade 1.
        db.update("INSERT INTO schedule_version(term_id,load_id,version_no,effective_from) VALUES"
                + "(?,?,1,'2026-03-01'),(?,?,2,'2026-03-02'),(?,?,3,'2026-03-03')",
                a, a + 1, a, a + 2, a, a + 1);
        var result = http(get(path), admin(a), null, 200);
        assertThat(result.get("loadId").longValue()).isEqualTo(a + 1);
        assertThat(result.at("/teachers/0/grades").toString()).isEqualTo("[3]");
        assertThat(result.at("/teachers/0/subjectIds").toString()).isEqualTo("[" + a + "," + (a + 1) + "]");
        assertThat(result.at("/teachers/1/grades").size()).isZero();
        assertThat(result.at("/teachers/1/subjectIds").size()).isZero();
        http(get(path), admin(b), null, 404);
    }

    @Test
    void namesakesAndTermSpecificSubjectsExistBeforeTheFirstLesson() throws Exception {
        long first = create(a, email, "김교사");
        long second = create(a, "peer" + email, "김교사");
        assertThat(second).isNotEqualTo(first);
        String item = base(a) + "/" + first;
        var changed = http(put(item), admin(a), Map.of("authEmail", email, "displayName", "김수학"), 200);
        assertThat(changed.required("displayName").asText()).isEqualTo("김수학");
        var fall = http(put(item + "/terms/" + (a + 1)), admin(a),
                Map.of("subjectIds", List.of(a), "homeroomClassId", a), 200).required("term");
        assertThat(fall.at("/homeroomClass/classId").longValue()).isEqualTo(a);
        http(put(item + "/terms/" + a), admin(a), Map.of("subjectIds", List.of(a, a + 1)), 200);
        var spring = http(get(item).param("termId", "" + a), admin(a), null, 200).required("term");
        assertThat(subjects(spring)).containsExactlyInAnyOrder(a, a + 1);
        assertThat(spring.required("homeroomClass").isNull()).isTrue();
        var rows = http(get(base(a)).param("termId", "" + a), admin(a), null, 200);
        assertThat(rows.size()).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from timetable t join school_user su on su.id=t.teacher_id where su.school_id=?",
                Long.class, a)).isZero();

        // Full replacement removes one subject without changing the other term.
        http(put(item + "/terms/" + a), admin(a), Map.of("subjectIds", List.of(a + 1)), 200);
        assertThat(subjects(http(get(item).param("termId", "" + a), admin(a), null, 200).required("term")))
                .containsExactly(a + 1);
        assertThat(subjects(http(get(item).param("termId", "" + (a + 1)), admin(a), null, 200).required("term")))
                .containsExactly(a);
    }

    List<Long> subjects(JsonNode term) {
        var ids = new ArrayList<Long>();
        term.required("subjects").forEach(subject -> ids.add(subject.required("subjectId").longValue()));
        return ids;
    }

    @Test
    void verifiedSignupLinksOnlyTheSelectedSchoolAndUsesProviderIdentityAfterAProfileEdit() throws Exception {
        long first = create(a, email, "이수학");
        long second = create(b, email, "이과학");
        String different = "other" + email;
        long unrelated = create(b, different, "다른 교사");
        var session = verify(email, a);
        long user = session.required("userId").longValue();
        String token = localSessionFor(session);
        assertThat(session.get("schoolMembership").isNull()).isTrue();
        http(post(ONBOARD), token, Map.of("role","TEACHER","schoolCode",code(a),"displayName","이수학"), 200);
        assertThat(members(user, a)).isOne();
        assertThat(members(user, b)).isZero();
        var me = http(get(base(a) + "/me"), token, null, 200);
        assertThat(me.required("rosterId").longValue()).isEqualTo(first);
        assertThat(me.required("displayName").asText()).isEqualTo("이수학");
        assertThat(http(get(base(b) + "/" + second), admin(b), null, 200).required("status").textValue())
                .isEqualTo("UNLINKED");
        http(put("/api/mypage/teacher/profile"), token, Map.of("username", "계정 이름", "email", different), 200);
        http(post(ADD_SCHOOL), token, Map.of("schoolCode", code(b), "displayName", "이과학"), 201);
        assertThat(members(user, a)).isOne();
        assertThat(members(user, b)).isOne();
        http(patch("/api/mypage/active-school/" + b), token, null, 200);
        assertThat(http(get(base(b) + "/me"), token, null, 200).required("rosterId").longValue()).isEqualTo(second);
        assertThat(http(get(base(b) + "/" + unrelated), admin(b), null, 200).required("status").textValue())
                .isEqualTo("UNLINKED");
        assertThat(verify(email, b).required("userId").longValue()).isEqualTo(user);
        http(put(base(b) + "/" + second), admin(b),
                Map.of("authEmail", "changed" + email, "displayName", "변경"), 409);
    }

    @Test
    void rejectingOnlyTheReviewedRequestAllowsReapplicationWithoutGrantingAccess() throws Exception {
        var account = verify(email, null);
        String teacher = localSessionFor(account);
        long user = account.required("userId").longValue();
        http(post(ONBOARD), teacher, onboarding(a), 202);
        long id = http(get(base(a) + "/me"), teacher, null, 200).required("rosterId").longValue();
        String path = base(a) + "/" + id;
        String timestamp = http(get(path + "/match-options"), admin(a), null, 200).required("requestedAt").asText();
        var request = Map.of("requestedAt", timestamp);
        http(post(path + "/reject"), teacher, request, 403);
        http(post(path + "/reject"), admin(b), request, 403);
        http(post(path + "/reject"), admin(a), request, 204);
        http(post(path + "/reject"), admin(a), request, 409);
        assertThat(http(get(ADD_SCHOOL), teacher, null, 200).size()).isZero();
        assertThat(members(user, a)).isZero();

        http(post(ONBOARD), teacher, onboarding(a), 202);
        http(post(path + "/reject"), admin(a), request, 409);
        http(post(path + "/approve"), admin(a), null, 200);
        http(post(path + "/reject"), admin(a), request, 409);
        assertThat(members(user, a)).isOne();
    }

    @Test
    void pendingRequestsAndApprovalRemainSingleAndSchoolScopedUnderConcurrentReplay() throws Exception {
        var session = verify(email, null);
        String token = localSessionFor(session);
        long user = session.required("userId").longValue();
        var responses = race(() -> http(post(ONBOARD), token, onboarding(a), 202));
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.required("employmentStatus").asText()).isEqualTo("PENDING");
            assertThat(r.required("schoolUserId").isNull()).isTrue();
        });
        assertThat(rosterCount(a, email)).isOne();
        assertThat(members(user, a)).isZero();
        var pending = http(get(base(a) + "/me"), token, null, 200);
        assertThat(pending.required("displayName").textValue()).isEqualTo("신청 교사");
        assertThat(pending.required("status").textValue()).isEqualTo("PENDING");
        var reopened = verify(email, null);
        var schools = http(get(ADD_SCHOOL), localSessionFor(reopened), null, 200);
        assertThat(schools.size()).isOne();
        assertThat(schools.get(0).required("schoolId").longValue()).isEqualTo(a);
        assertThat(schools.get(0).required("employmentStatus").textValue()).isEqualTo("PENDING");
        http(get("/api/school/teachers"), token, null, 403);
        http(post("/api/voice-commands/preview"), token, Map.of("text", "보결 요청"), 403);
        http(get(base(a)), token, null, 403);
        String approval = base(a) + "/" + pending.required("rosterId").longValue() + "/approve";
        http(post(approval), token, null, 403);
        http(post(approval), admin(b), null, 403);
        assertThat(members(user, a)).isZero();

        var approvals = race(() -> http(post(approval), admin(a), null, 200));
        assertThat(approvals).allSatisfy(r -> assertThat(r.required("status").asText()).isEqualTo("LINKED"));
        long member = approvals.get(0).required("schoolUserId").longValue();
        assertThat(approvals.get(1).required("schoolUserId").longValue()).isEqualTo(member);
        assertThat(http(post(ONBOARD), token, onboarding(a), 200).required("schoolUserId").longValue()).isEqualTo(member);
        assertThat(rosterCount(a, email)).isOne();
        assertThat(members(user, a)).isOne();
        http(get("/api/school/teachers"), token, null, 200);
    }

    @Test
    void aStaleProposalLeavesBothRowsUnchangedUntilTheTeacherAcceptsTheNewNames() throws Exception {
        long firstId = create(a, email, "선등록 1");
        long secondId = create(a, "peer" + email, "선등록 2");
        String first = localSessionFor(verify(email, null));
        String second = localSessionFor(verify("peer" + email, null));
        var input = Map.of("schoolCode", code(a), "displayName", "김민지", "role", "TEACHER");
        var old = http(post(ONBOARD + "/name-preview"), second, input, 200);
        var firstMember = http(post(ONBOARD), first, input, 200).required("schoolUserId");
        var commit = new HashMap<>(input);
        commit.put("nameConfirmation", old.required("confirmation").asText());
        var conflict = http(post(ONBOARD), second, commit, 409);
        assertThat(conflict.required("code").asText()).isEqualTo("NAME_CONFIRMATION_REQUIRED");
        var changed = conflict.required("proposal");
        assertThat(changed.required("renamedTo").asText()).isEqualTo("김민지A");
        assertThat(changed.required("displayName").asText()).isEqualTo("김민지B");
        assertThat(http(get(base(a) + "/" + firstId), admin(a), null, 200).required("displayName").asText()).isEqualTo("김민지");
        assertThat(http(get(base(a) + "/" + secondId), admin(a), null, 200).required("status").asText()).isEqualTo("UNLINKED");
        commit.put("nameConfirmation", changed.required("confirmation").asText());
        var accepted = http(post(ONBOARD), second, commit, 200);
        assertThat(http(post(ONBOARD), second, commit, 200).required("schoolUserId")).isEqualTo(accepted.required("schoolUserId"));
        var renamed = http(get(base(a) + "/me"), first, null, 200);
        assertThat(renamed.required("displayName").asText()).isEqualTo("김민지A");
        assertThat(renamed.required("schoolUserId")).isEqualTo(firstMember);
    }

    @Test
    void concurrentClaimsOnAdministratorRosterReuseOneEmployment() throws Exception {
        long id = create(a, email, "이교사");
        var session = verify(email, null);
        String token = localSessionFor(session);
        var outcomes = race(() -> http(post(ONBOARD), token, onboarding(a), 200));
        long member = outcomes.get(0).required("schoolUserId").longValue();
        assertThat(outcomes.get(1).required("schoolUserId").longValue()).isEqualTo(member);
        assertThat(http(post(ONBOARD), token, onboarding(a), 200).required("schoolUserId").longValue()).isEqualTo(member);
        assertThat(members(session.required("userId").longValue(), a)).isOne();
        assertThat(rosterCount(a, email)).isOne();
        assertThat(http(get(base(a) + "/me"), token, null, 200).required("rosterId").longValue()).isEqualTo(id);
        http(put(base(a) + "/" + id + "/terms/" + a), admin(a),
                Map.of("subjectIds", List.of(a, a + 1), "homeroomClassId", a), 200);
        var teachers = http(get("/api/school/teachers").param("termId", "" + a), token, null, 200);
        assertThat(teachers.size()).isOne();
        var summary = teachers.get(0);
        assertThat(summary.required("displayName").textValue()).isEqualTo("신청 교사");
        assertThat(summary.required("subjects").size()).isZero();
        assertThat(summary.required("weeklyLessonCount").intValue()).isZero();
        assertThat(summary.required("rosterSubjects").size()).isEqualTo(2);
        assertThat(summary.at("/rosterHomeroomClass/classId").longValue()).isEqualTo(a);
        assertThat(summary.has("authEmail")).isFalse();
        http(get("/api/school/teachers").param("termId", "" + b), token, null, 404);
    }

    @Test
    void onlyOneConcurrentVerificationConsumesTheCodeAndExplicitConfirmationLinksTheSchool() throws Exception {
        long id = create(a, email, "김과학");
        var payload = json.writeValueAsBytes(Map.of("email", email, "code", sendCode(email), "schoolCode", code(a)));
        var results = race(() -> mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn().getResponse());
        assertThat(results).extracting(r -> r.getStatus()).containsExactlyInAnyOrder(200, 400);
        var success = results.stream().filter(r -> r.getStatus() == 200).findFirst().orElseThrow();
        var account = json.readTree(success.getContentAsByteArray());
        assertThat(account.get("schoolMembership").isNull()).isTrue();
        assertThat(members(account.required("userId").longValue(), a)).isZero();
        http(post(ONBOARD), localSessionFor(account), Map.of("role","TEACHER","schoolCode",code(a),"displayName","김과학"), 200);
        assertThat(members(account.required("userId").longValue(), a)).isOne();
        assertThat(rosterCount(a, email)).isOne();
        assertThat(http(get(base(a) + "/" + id), admin(a), null, 200).required("status").textValue()).isEqualTo("LINKED");
    }

    @Test
    void alreadyLinkedRowAndCurrentEmploymentAreCheckedOnEveryRequest() throws Exception {
        long id = create(a, email, "교사");
        var first = verify(email, a);
        String token = localSessionFor(first);
        long user = first.required("userId").longValue();
        long member = http(post(ONBOARD), token, onboarding(a), 200).required("schoolUserId").longValue();
        http(get("/api/school/teachers"), token, null, 200);
        db.update("insert into notifications(user_id,school_id,category,type,message,is_read,created_at) values "
                        + "(?,?,'SCHEDULE_INPUT','SCHEDULE_INPUT_REQUEST','school notice',false,CURRENT_TIMESTAMP),"
                        + "(?,null,'SCHEDULE_INPUT','SCHEDULE_INPUT_REQUEST','personal notice',false,CURRENT_TIMESTAMP)",
                user, a, user);
        assertThat(http(get("/api/notifications"), token, null, 200).findValuesAsText("message"))
                .containsExactlyInAnyOrder("school notice", "personal notice");
        db.update("update school_user set employment_status='RESIGNED' where id=?", member);
        assertThat(http(get("/api/notifications"), token, null, 200).findValuesAsText("message"))
                .containsExactly("personal notice");
        http(get("/api/school/teachers"), token, null, 403);
        http(post("/api/voice-commands/preview"), token, Map.of("text", "보결 요청"), 403);
        http(get(base(a) + "/me"), token, null, 404);
        db.update("update school_user set employment_status='ON_LEAVE' where id=?", a);
        http(get(base(a)), admin(a), null, 403);
        http(post(base(a) + "/" + id + "/approve"), admin(a), null, 403);

        // Signing in from a second account with an OTP cannot transfer the first account's link.
        db.update("insert into users(id,username,provider,provider_id) values(?,'second','kakao',?)",
                a + 5, "oauth-second-" + a);
        http(post(VERIFY), jwt.generateAccess(a + 5),
                Map.of("email", email, "code", sendCode(email), "schoolCode", code(a)), 409);
        assertThat(members(a + 5, a)).isZero();
        assertThat(members(user, a)).isOne();
        assertThat(db.queryForObject("select school_user_id from school_teacher_roster where id=?", Long.class, id))
                .isEqualTo(member);
    }

    @Test
    void foreignObjectsCannotModifyATermOrLeakAnotherSchoolsRoster() throws Exception {
        long id = create(a, email, "김영어");
        String term = base(a) + "/" + id + "/terms/";
        http(put(term + a), admin(a), Map.of("subjectIds", List.of(a), "homeroomClassId", a), 200);
        http(put(term + b), admin(a), Map.of("subjectIds", List.of(a)), 404);
        http(put(term + a), admin(a), Map.of("subjectIds", List.of(a + 1, b)), 404);
        http(put(term + a), admin(a), Map.of("subjectIds", List.of(a + 1), "homeroomClassId", b), 404);
        http(put(term + a), admin(a), Map.of("subjectIds", List.of(a + 1), "homeroomClassId", a + 1), 404);
        var retained = http(get(base(a) + "/" + id).param("termId", "" + a), admin(a), null, 200).required("term");
        assertThat(subjects(retained)).containsExactly(a);
        assertThat(retained.at("/homeroomClass/classId").longValue()).isEqualTo(a);
        http(get(base(a)), admin(b), null, 403);
        http(get(base(b) + "/" + id), admin(b), null, 404);
        http(get(base(a) + "/me"), admin(b), null, 404);
        http(post(base(b)), admin(a), Map.of("authEmail", email, "displayName", "김교사"), 403);
        http(post(base(a)), admin(a), Map.of("authEmail", email, "displayName", "다른 이름"), 409);
    }

    @Test
    void invalidVerificationUnknownSchoolAndUnverifiedAccountCannotClaimRoster() throws Exception {
        create(a, email, "김교사");
        var invalid = http(post(VERIFY), null, Map.of("email", email, "code", "000000", "schoolCode", code(a)), 400);
        assertThat(invalid.required("success").booleanValue()).isFalse();
        assertThat(rosterCount(a, email)).isOne();
        assertThat(db.queryForObject("select count(*) from roster_verified_account where auth_email=?", Long.class, email))
                .isZero();
        var session = verify(email, null);
        String token = localSessionFor(session);
        http(post(ONBOARD), token, Map.of("schoolCode", "missing-" + a, "role", "TEACHER"), 400);
        assertThat(members(session.required("userId").longValue(), a)).isZero();

        long unverified = a + 7;
        db.update("insert into users(id,username,email,provider,provider_id) values(?,'교사',?,'kakao',?)",
                unverified, email, "oauth-" + a);
        http(post(ONBOARD), jwt.generateAccess(unverified), onboarding(a), 403);
        http(post(ADD_SCHOOL), jwt.generateAccess(unverified), Map.of("schoolCode", code(a)), 403);
        assertThat(members(unverified, a)).isZero();
        assertThat(http(get(base(a)), admin(a), null, 200).get(0).required("status").textValue()).isEqualTo("UNLINKED");

        // A different valid school code creates a pending request there, even though this email is on A's roster.
        http(post(ADD_SCHOOL), token, Map.of("schoolCode", code(b), "displayName", "신청 교사"), 202);
        assertThat(members(session.required("userId").longValue(), b)).isZero();
        assertThat(http(get(base(a)), admin(a), null, 200).get(0).required("status").textValue()).isEqualTo("UNLINKED");
        assertThat(http(get(base(b)), admin(b), null, 200).get(0).required("status").textValue()).isEqualTo("PENDING");
        // The one-step email signup, before this applicant has an account or a roster, takes the same approval path.
        String newcomer = "new" + email;
        var direct = http(post(VERIFY), null, Map.of("email", newcomer, "code", sendCode(newcomer),
                "schoolCode", code(a), "displayName", "신규 교사"), 200);
        assertThat(direct.get("schoolMembership").isNull()).isTrue();
        http(post(ONBOARD), localSessionFor(direct), Map.of("role","TEACHER","schoolCode",code(a),"displayName","신규 교사"), 202);
        assertThat(members(direct.required("userId").longValue(), a)).isZero();
        assertThat(http(get(base(a) + "/me"), localSessionFor(direct), null, 200)
                .required("displayName").textValue()).isEqualTo("신규 교사");
    }

    @Test
    void legacyLoginHandlesAnOrphanSchoolAndUnusableProfileNamesWithoutOverwritingThem() throws Exception {
        long orphan = a + 4;
        db.update("insert into users(id,username,provider,provider_id,active_school_id) values(?,'old','email',?,?)",
                orphan, email, b + 500);
        var signedIn = verify(email, null);
        assertThat(signedIn.required("userId").longValue()).isEqualTo(orphan);
        assertThat(signedIn.required("schoolMembership").isNull()).isTrue();
        assertThat(db.queryForObject("select count(*) from school_user where user_id=?", Long.class, orphan)).isZero();

        List<String> names = List.of(" ", "가".repeat(101));
        for (int i = 0; i < names.size(); i++) {
            long user = a + 5 + i;
            String address = "old" + i + email;
            db.update("insert into users(id,username,provider,provider_id,active_school_id) values(?,?,'email',?,?)",
                    user, names.get(i), address, a);
            db.update("insert into school_user(id,user_id,school_id,position,employment_status) values(?,?,?,'TEACHER','HIRED')",
                    user, user, a);
            var session = verify(address, null);
            assertThat(session.at("/schoolMembership/schoolUserId").longValue()).isEqualTo(user);
            http(get(base(a) + "/me"), localSessionFor(session), null, 404);
            assertThat(rosterCount(a, address)).isZero();
            assertThat(db.queryForObject("select username from users where id=?", String.class, user)).isEqualTo(names.get(i));
        }
    }

    @Test
    void migrationAndAccountLinkPreserveLegacyNameMemberIdHireDateAndOtherSchool() throws Exception {
        long user = a + 4;
        String localPart = email.substring(0, email.indexOf('@'));
        db.update("insert into users(id,username,email,provider,provider_id,active_school_id) values(?,?,?,'email',?,?)",
                user, localPart, email, email, a);
        for (long school : List.of(a, b)) {
            db.update("insert into school_user(id,user_id,school_id,position,employment_status,hire_date)"
                            + " values(?,?,?,'TEACHER',?,'2022-03-01')",
                    school + 4, user, school, school == a ? "HIRED" : "ON_LEAVE");
        }
        migrate(source);
        create(a, email, "학교 표시 이름");
        var loggedIn = verify(email, a);
        assertThat(loggedIn.required("userId").longValue()).isEqualTo(user);
        assertThat(loggedIn.at("/schoolMembership/schoolUserId").longValue()).isEqualTo(a + 4);
        assertThat(db.queryForObject("select username from users where id=?", String.class, user)).isEqualTo(localPart);
        for (long school : List.of(a, b)) {
            assertThat(members(user, school)).isOne();
            var member = db.queryForMap("select id, position, employment_status, hire_date from school_user where user_id=? and school_id=?",
                    user, school);
            assertThat(((Number) member.get("id")).longValue()).isEqualTo(school + 4);
            assertThat(member.get("position")).isEqualTo("TEACHER");
            assertThat(member.get("employment_status")).isEqualTo(school == a ? "HIRED" : "ON_LEAVE");
            assertThat(member.get("hire_date").toString()).isEqualTo("2022-03-01");
        }
        String token = localSessionFor(loggedIn);
        var preview = http(post(ONBOARD + "/name-preview"), token,
                Map.of("schoolCode", code(a), "displayName", "본인이 확인한 이름"), 200);
        assertThat(http(get(base(a)), admin(a), null, 200).get(0).required("status").asText()).isEqualTo("UNLINKED");
        var confirmed = http(post(ONBOARD), token, Map.of("role", "TEACHER", "schoolCode", code(a),
                "displayName", "본인이 확인한 이름", "nameConfirmation", preview.required("confirmation").asText()), 200);
        assertThat(confirmed.required("schoolUserId").longValue()).isEqualTo(a + 4);
        assertThat(http(get(base(a) + "/me"), token, null, 200).required("displayName").asText()).isEqualTo("본인이 확인한 이름");
        assertThat(db.queryForObject("select username from users where id=?", String.class, user)).isEqualTo(localPart);
        assertThat(rosterCount(b, email)).isZero();
        http(post(ONBOARD), token, onboarding(b), 403);
        http(patch("/api/mypage/active-school/" + b), token, null, 403);
    }
}
