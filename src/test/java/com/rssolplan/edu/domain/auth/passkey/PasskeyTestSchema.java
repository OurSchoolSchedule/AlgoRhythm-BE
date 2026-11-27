package com.rssolplan.edu.domain.auth.passkey;

import org.springframework.test.context.jdbc.Sql;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Sql({
    "/db/migration/V20261122_01__school_load_and_schedule_versions.sql",
    "/db/migration/V20261124_01__generation_and_lesson_operations.sql",
    "/db/migration/V20261125_11__school_teacher_roster.sql",
    "/db/migration/V20261126_31__school_roster_names.sql",
    "/db/migration/V20261127_41__roster_teaching_link.sql",
    "/db/migration/V20261125_21__passkey_auth.sql"
})
public @interface PasskeyTestSchema {}
