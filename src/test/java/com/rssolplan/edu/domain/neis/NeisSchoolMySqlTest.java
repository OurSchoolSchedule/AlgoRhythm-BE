package com.rssolplan.edu.domain.neis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.domain.schedule.candidates.School42Round;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.*;
import java.util.concurrent.*;

import static com.rssolplan.edu.domain.neis.NeisClient.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@ActiveProfiles("local")
@AutoConfigureMockMvc
@SpringBootTest(properties = {"neis.enabled=true","app.auth.dev-login-enabled=true","app.passkeys.environment=local"})
@EnabledIfEnvironmentVariable(named = "NEIS_MYSQL_TEST", matches = "1")
@Sql({"/db/migration/V20261122_01__school_load_and_schedule_versions.sql",
        "/db/migration/V20261124_01__generation_and_lesson_operations.sql",
        "/db/migration/V20261125_11__school_teacher_roster.sql","/db/migration/V20261125_21__passkey_auth.sql",
        "/db/migration/V20261125_22__roster_import_preview.sql","/db/migration/V20261125_23__neis_school_link.sql"})
class NeisSchoolMySqlTest {
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired ObjectMapper json;
    @Autowired SchoolSearchCache cache;
    @Autowired StringRedisTemplate redis;
    @Autowired School42Round rehearsal;
    @MockitoBean NeisClient client;

    final long school = ThreadLocalRandom.current().nextLong(10_000_000_000L, 90_000_000_000L);
    final long admin = school, other = school+100;
    final String search = "/api/neis/schools/search";
    final String link = "/api/neis/schools/" + school + "/link";

    @BeforeEach void setup() {
        db.update("INSERT INTO school(id,name) VALUES(?,'같은 이름'),(?,'같은 이름')", school,other);
        member(admin,school,"ADMIN","HIRED");
        member(other,other,"ADMIN","HIRED");
        when(client.fetchPage(eq(Dataset.SCHOOL), anyMap(), anyInt(), anyInt())).thenAnswer(inv -> {
            Map<String,String> f = inv.getArgument(1);
            if (f.containsKey("SD_SCHUL_CODE")) return found(f.get("SD_SCHUL_CODE"));
            return new Page(Status.NO_DATA, inv.getArgument(2), inv.getArgument(3), 0, List.of());
        });
    }

    private static Map<String,String> row(String office, String code, String address) {
        return Map.of("ATPT_OFCDC_SC_CODE",office,"SD_SCHUL_CODE",code,"SCHUL_NM","경신고등학교",
                "SCHUL_KND_SC_NM","고등학교","ORG_RDNMA",address);
    }
    private static Page found(String code) {
        return new Page(Status.OK,1,2,1,List.of(row("B10",code,"서울")));
    }

    void member(long user,long site,String role,String employment) {
        db.update("INSERT INTO users(id,username,active_school_id) VALUES(?,'테스트',?)",user,site);
        db.update("INSERT INTO school_user(user_id,school_id,position,employment_status) VALUES(?,?,?,?)",
                user,site,role,employment);
    }

    JsonNode request(MockHttpServletRequestBuilder req, long user, int status) throws Exception {
        if (user != 0) req.header("Authorization","Bearer " + jwt.generateAccess(user));
        var result = mvc.perform(req).andReturn().getResponse();
        assertThat(result.getStatus()).as(result.getContentAsString()).isEqualTo(status);
        JsonNode response = json.readTree(result.getContentAsByteArray());
        if (status >= 400) {
            assertThat(response.path("success").asBoolean(true)).isFalse();
            assertThat(response.get("data").isNull()).isTrue();
            assertThat(response.path("error").asText()).isNotBlank();
        }
        return response;
    }

    MockHttpServletRequestBuilder change(String path,String code,long version) {
        return patch(path).contentType("application/json").content("""
                {"officeCode":"B10","standardSchoolCode":"%s","expectedVersion":%d}
                """.formatted(code,version));
    }

    int audits(long site) {
        return db.queryForObject("SELECT count(*) FROM school_neis_link_audit WHERE school_id=?",Integer.class,site);
    }

    @Test void permissionsAtTheHttpBoundary() throws Exception {
        member(school+1,school,"TEACHER","HIRED");
        member(school+2,school,"ADMIN","ON_LEAVE");
        member(school+3,school,"ADMIN","RESIGNED");
        db.update("INSERT INTO users(id,username) VALUES(?,'신규'),(?,'승인 대기')",school+4,school+5);
        db.update("INSERT INTO school_teacher_roster(school_id,auth_email,display_name,pending_user_id) VALUES(?,'p@x.invalid','대기',?)",school,school+5);
        // The other admin also holds an inactive ADMIN membership in this school.
        db.update("INSERT INTO school_user(user_id,school_id,position,employment_status) VALUES(?,?,'ADMIN','HIRED')",other,school);
        for (long[] role : new long[][]{
                {0,401,401,401},{school+4,200,403,403},{school+5,403,403,403},
                {school+1,403,200,403},{school+2,403,403,403},{school+3,403,403,403},
                {other,200,403,403},{admin,200,200,200}}) {
            request(get(search).param("q","학교"), role[0], (int)role[1]);
            var read = request(get(link),role[0],(int)role[2]);
            if (role[2] == 200) assertThat(read.path("status").asText()).isEqualTo("NOT_LINKED");
            request(change(link,"7010057",0),role[0],(int)role[3]);
        }
        assertThat(request(get(link).header("Authorization","Bearer invalid"),0,401).path("error").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test void retryAndIndependentSchoolsAndRollbackOnExternalFailure() throws Exception {
        var first = request(change(link,"7010057",0),admin,200);
        assertThat(first.path("version").asLong()).isOne();
        var otherLink = "/api/neis/schools/"+other+"/link";
        request(change(otherLink,"7010057",0),other,200);
        when(client.fetchPage(any(),anyMap(),anyInt(),anyInt())).thenThrow(new Unavailable());
        assertThat(request(change(link,"7010057",0),admin,200)).isEqualTo(first);
        assertThat(request(change(link,"7010125",1),admin,503).path("error").asText()).isEqualTo("NEIS_UNAVAILABLE");
        assertThat(request(get(link),admin,200)).isEqualTo(first);
        assertThat(audits(school)).isOne();
        assertThat(audits(other)).isOne();
        assertThat(request(get(otherLink),other,200).path("schoolId").asLong()).isEqualTo(other);
    }

    @Test void snapshotAndAuditAreOneTransaction() throws Exception {
        var first = request(change(link,"7010057",0),admin,200);
        db.update("""
                INSERT INTO school_neis_link_audit(school_id,link_version,changed_at,changed_by,new_office_code,new_school_code)
                VALUES(?,2,UTC_TIMESTAMP(6),?,'B10','fixture')
                """,school,admin);
        try {
            assertThat(request(change(link,"7010125",1),admin,500).path("error").asText()).isEqualTo("INTERNAL_ERROR");
            assertThat(request(get(link),admin,200)).isEqualTo(first);
            assertThat(audits(school)).isEqualTo(2);
        } finally {
            db.update("DELETE FROM school_neis_link_audit WHERE school_id=? AND link_version=2",school);
        }
        var second = request(change(link,"7010125",1),admin,200);
        assertThat(second.path("version").asLong()).isEqualTo(2);
        assertThat(audits(school)).isEqualTo(2);
    }

    @Test void competingAdminsOneCommitOneConflict() throws Exception {
        request(change(link,"7010057",0),admin,200);
        member(school+1,school,"ADMIN","HIRED");
        var bothVerified = new CyclicBarrier(2);
        when(client.fetchPage(any(),anyMap(),anyInt(),anyInt())).thenAnswer(inv -> {
            Map<String,String> filters = inv.getArgument(1);
            bothVerified.await(8, TimeUnit.SECONDS);
            return found(filters.get("SD_SCHUL_CODE"));
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> race(admin,"7010125"));
            var b = pool.submit(() -> race(school+1,"7010126"));
            assertThat(List.of(a.get(12,TimeUnit.SECONDS), b.get(12,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally { pool.shutdownNow(); }
        assertThat(request(get(link),admin,200).path("version").asLong()).isEqualTo(2);
        assertThat(audits(school)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT previous_school_code FROM school_neis_link_audit WHERE school_id=? AND link_version=2",String.class,school)).isEqualTo("7010057");
    }

    int race(long user,String code) throws Exception {
        var r = mvc.perform(change(link,code,1).header("Authorization","Bearer "+jwt.generateAccess(user))).andReturn().getResponse();
        if (r.getStatus()==409) assertThat(json.readTree(r.getContentAsByteArray()).path("error").asText()).isEqualTo("NEIS_LINK_CHANGED");
        return r.getStatus();
    }

    @Test void rechecksMembershipAndActiveSchoolAfterProviderCall() throws Exception {
        when(client.fetchPage(any(),anyMap(),anyInt(),anyInt())).thenAnswer(inv -> {
            db.update("UPDATE users SET active_school_id=? WHERE id=?",other,admin);
            return found("7010057");
        });
        request(change(link,"7010057",0),admin,403);
        db.update("UPDATE users SET active_school_id=? WHERE id=?",school,admin);
        doAnswer(inv -> {
            db.update("UPDATE school_user SET employment_status='ON_LEAVE' WHERE school_id=? AND user_id=?",school,admin);
            return found("7010057");
        }).when(client).fetchPage(any(),anyMap(),anyInt(),anyInt());
        request(change(link,"7010057",0),admin,403);
        assertThat(audits(school)).isZero();
    }

    @Test void searchFiltersCacheLimitAndEmptyResult() throws Exception {
        String q = "경신" + school;
        var filters = Map.of("SCHUL_NM",q,"ATPT_OFCDC_SC_CODE","B10","SCHUL_KND_SC_NM","고등학교");
        var rows = List.of(row("B10","7010125","서울"),row("D10","7240073","대구"),row("B10"," ","서울"));
        // Unfiltered homonyms, then a distinct office/kind/page cache entry.
        when(client.fetchPage(Dataset.SCHOOL,Map.of("SCHUL_NM",q),1,3)).thenReturn(new Page(Status.OK,1,3,4,rows));
        var data = request(get(search).param("q"," "+q+" ").param("size","3"),admin,200);
        assertThat(data.path("hasNext").asBoolean()).isTrue();
        assertThat(data.path("items")).hasSize(3);
        assertThat(data.at("/items/0/address").asText()).isEqualTo("서울");
        assertThat(data.at("/items/1/officeCode").asText()).isEqualTo("D10");
        assertThat(data.at("/items/2/selectable").asBoolean()).isFalse();
        assertThat(data.at("/items/2/standardSchoolCode").isNull()).isTrue();
        request(get(search).param("q",q).param("size","3"),admin,200);
        verify(client,times(1)).fetchPage(Dataset.SCHOOL,Map.of("SCHUL_NM",q),1,3);
        assertThat(redis.getExpire(cache.key(q,null,null,1,3))).isBetween(86300L,86400L);
        var empty = request(get(search).param("q",q).param("officeCode","B10").param("schoolKind","고등학교")
                .param("page","2").param("size","3"),admin,200);
        verify(client).fetchPage(Dataset.SCHOOL,filters,2,3);
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("totalCount").asInt()).isZero();
        assertThat(empty.path("hasNext").asBoolean()).isFalse();
        for (int i=3; i<30; i++) request(get(search).param("q",q).param("size","3"),admin,200);
        assertThat(request(get(search).param("q",q),admin,429).path("error").asText()).isEqualTo("NEIS_RATE_LIMITED");
        request(get(search).param("q",q),other,200);
        assertThat(redis.getExpire("neis:v1:search-limit:"+admin)).isBetween(1L,60L);
    }

    @Test void rejectsBadInputUnconfirmedPairAndSchool42() throws Exception {
        request(get(search).param("q","가"),admin,400);
        request(get(search).param("q","고교").param("size","51"),admin,400);
        request(get(search).param("q","고교").param("page","x"),admin,400);
        for (String bad : List.of("{}", "{", "{\"officeCode\":\" \",\"standardSchoolCode\":\"1\",\"expectedVersion\":0}",
                "{\"officeCode\":\"B10\",\"standardSchoolCode\":\"1\",\"expectedVersion\":0,\"userId\":7}"))
            request(patch(link).contentType("application/json").content(bad),admin,400);
        when(client.fetchPage(any(),anyMap(),anyInt(),anyInt())).thenReturn(new Page(Status.NO_DATA,1,2,0,List.of()));
        assertThat(request(change(link,"123",0),admin,404).path("error").asText()).isEqualTo("NEIS_SCHOOL_NOT_FOUND");
        when(client.fetchPage(any(),anyMap(),anyInt(),anyInt())).thenReturn(found("456"));
        request(change(link,"123",0),admin,503);
        assertThat(audits(school)).isZero();
        rehearsal.prepareTable();
        rehearsal.open();
        request(change("/api/neis/schools/540001/link","7010057",0),541000,403);
        long round = db.queryForObject("SELECT round_no FROM school42_rehearsal WHERE school_id=540001",Long.class);
        db.update("UPDATE school42_rehearsal SET round_no=0 WHERE school_id=540001");
        try {
            request(change("/api/neis/schools/540001/link","7010057",0),541000,403);
        } finally {
            db.update("UPDATE school42_rehearsal SET round_no=? WHERE school_id=540001",round);
        }
        assertThat(audits(540001)).isZero();
    }
}
