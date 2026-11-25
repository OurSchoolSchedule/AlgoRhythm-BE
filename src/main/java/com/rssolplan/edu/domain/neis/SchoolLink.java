package com.rssolplan.edu.domain.neis;

import java.time.Instant;

public record SchoolLink(long schoolId, Status status, long version, SchoolOption school, Instant linkedAt) {
    public enum Status { NOT_LINKED, LINKED }

    static SchoolLink unlinked(long schoolId) {
        return new SchoolLink(schoolId, Status.NOT_LINKED, 0, null, null);
    }

    boolean matches(String office, String code) {
        return school != null && office.equals(school.officeCode()) && code.equals(school.standardSchoolCode());
    }
}
