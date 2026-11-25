package com.rssolplan.edu.domain.neis;

public record SchoolOption(String officeCode, String standardSchoolCode, String officeName,
                           String schoolName, String schoolKind, String address, boolean selectable) {
    static SchoolOption of(String office, String code, String officeName, String name, String kind, String address) {
        office = clean(office);
        code = clean(code);
        return new SchoolOption(office, code, clean(officeName), clean(name), clean(kind), clean(address),
                office != null && code != null);
    }

    static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
