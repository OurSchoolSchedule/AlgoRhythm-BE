package com.rssolplan.edu.domain.schedule.candidates;

import com.rssolplan.edu.domain.auth.passkey.PasskeyTestSchema;
import com.rssolplan.edu.domain.schedule.operations.ScheduleConflict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@PasskeyTestSchema
@Transactional
@EnabledIfEnvironmentVariable(named = "DEMO_RESET_DB_TEST", matches = "1")
class RehearsalApplicantProtectionTest {
    @Autowired JdbcTemplate db;
    @Autowired School42Round rounds;
    long member;
    long roster;

    @BeforeEach
    void prepareApplicant() {
        assertThat(db.queryForObject("select database()", String.class)).isEqualTo("demo_reset_test");
        rounds.prepareTable();
        rounds.open();
        db.update("insert into users(provider,provider_id,active_school_id) values ('email',?,540001)", RehearsalApplicant.EMAIL);
        long user = db.queryForObject("select last_insert_id()", Long.class);
        db.update("insert into roster_verified_account(user_id,auth_email) values (?,?)", user, RehearsalApplicant.EMAIL);
        db.update("insert into school_user(user_id,school_id,position,employment_status) values (?,540001,'TEACHER','HIRED')", user);
        member = db.queryForObject("select last_insert_id()", Long.class);
        db.update("insert into school_teacher_roster(school_id,auth_email,display_name,school_user_id) values (540001,?,'발표자',?)", RehearsalApplicant.EMAIL, member);
        roster = db.queryForObject("select last_insert_id()", Long.class);
        assertThat(rounds.timetableScope(School42Round.Mode.FULL).applicant().memberId()).isEqualTo(member);
    }

    @Test void aPreservedLoadPreventsResetEvenAfterPreview() {
        var before = rounds.timetableScope(School42Round.Mode.FULL);
        db.update("insert into load_import(term_id,version_no,created_by,normalized_sha,sheet_name) values (547001,19,?,'protected','시수표')", member);
        assertThatThrownBy(() -> rounds.resetTimetables(before)).isInstanceOf(ScheduleConflict.class);
        assertProtected();
        assertThat(db.queryForObject("select count(*) from load_import where created_by=?", Long.class, member)).isOne();
    }

    @Test void excludesInactiveEmploymentHistoryFromTheReset() {
        db.update("update school_user set employment_status='RESIGNED' where id=?", member);
        assertProtected();
    }

    @Test void anotherSchoolsTeacherMappingCannotBeRemovedByCascade() {
        db.update("insert into school(id,name) values (995500,'원본 학교')");
        db.update("insert into school_user(user_id,school_id,position,employment_status) values (541006,995500,'TEACHER','HIRED')");
        long unrelatedMembership = db.queryForObject("select last_insert_id()", Long.class);
        db.update("insert into roster_teaching_link(roster_id,teacher_school_user_id) values (?,?)", roster, unrelatedMembership);
        assertProtected();
        assertThat(db.queryForObject("select teacher_school_user_id from roster_teaching_link where roster_id=?", Long.class, roster)).isEqualTo(unrelatedMembership);
    }

    @Test void protectsAnotherTermsDatedTeacherWithoutForeignKey() {
        db.update("insert into timetable_set(school_id,academic_year,semester,start_date,end_date) values (540001,2032,2,'2032-09-01','2032-12-01')");
        long otherTerm = db.queryForObject("select last_insert_id()", Long.class);
        db.update("insert into schedule_version(term_id,version_no,effective_from) values (?,1,'2032-09-01')", otherTerm);
        long version = db.queryForObject("select last_insert_id()", Long.class);
        db.update("insert into weekly_lesson(version_id,lesson_key,class_id,subject_id,teacher_id,weekday,period_id) values (?,'other-term',544001,543001,542004,1,546001)", version);
        long lesson = db.queryForObject("select last_insert_id()", Long.class);
        db.update("insert into dated_lesson(origin_id,origin_date,teaching_date,planned,actual) values (?,'2032-09-01','2032-09-01',json_object('teacherId',542004),json_object('teacherId',?))", lesson, member);
        assertProtected();
    }

    @Test void protectsLegacyCandidateListsWithoutMembershipForeignKey() {
        db.update("insert into timetable(school_id,academic_year,semester,school_class_id,period_setting_id,day_of_week,subject_id,teacher_id) values (540001,2026,2,544001,546001,'MON',543001,542004)");
        long slot = db.queryForObject("select last_insert_id()", Long.class);
        db.update("insert into substitute_requests(school_id,owner_id,timetable_id,substitute_date,receiver_user_ids,status,version) values (540001,542000,?,'2026-11-23',?,'OPEN',0)", slot,"542002, " + member);
        assertProtected();
    }

    private void assertProtected() {
        assertThatThrownBy(() -> rounds.timetableScope(School42Round.Mode.FULL))
                .isInstanceOfSatisfying(ScheduleConflict.class, ex -> assertThat(ex.code).isEqualTo("SCHOOL42_APPLICANT"));
        assertThat(db.queryForObject("select count(*) from school_user where id=?", Long.class, member)).isOne();
    }
}
