package com.rssolplan.edu.domain.roster.bulk;

import com.rssolplan.edu.domain.roster.TeacherRosterService;
import com.rssolplan.edu.domain.roster.TeacherRosterService.*;
import com.rssolplan.edu.domain.schedule.loadimport.LoadProblem.Issue;
import com.rssolplan.edu.global.exception.BadRequestException;

import java.util.*;

import static com.rssolplan.edu.domain.roster.bulk.RosterWorkbook.issue;

final class RosterImportPlan {
    enum Action { ADD, UPDATE, UNCHANGED }
    record Target(String displayName, List<SubjectItem> subjects, Homeroom homeroomClass) {}
    record Change(String authEmail, List<Integer> rows, Action action, Entry before, Target after) {}
    record Counts(long add, long update, long unchanged, int errors) {}
    record Result(List<Change> changes, List<Issue> errors) {
        Counts counts() {
            return new Counts(count(Action.ADD), count(Action.UPDATE), count(Action.UNCHANGED), errors.size());
        }
        private long count(Action action) {
            return changes.stream().filter(change -> change.action() == action).count();
        }
    }
    record Catalog(int year, int semester, Map<Long, SubjectItem> subjects, Map<Long, Homeroom> classes) {}
    private static final class Teacher {
        final String email;
        final List<Integer> rows = new ArrayList<>();
        final SortedMap<Long, SubjectItem> subjects = new TreeMap<>();
        String name;
        int emptySubjectRow;
        Homeroom homeroom;
        int homeroomRow;
        Teacher(String email) { this.email = email; }
    }

    static Result build(RosterWorkbook.Input input, Catalog catalog, List<Entry> roster) {
        var errors = new ArrayList<>(input.errors());
        Map<String, Teacher> teachers = new LinkedHashMap<>();
        for (var row : input.rows()) {
            Teacher teacher;
            try {
                String email = TeacherRosterService.normalizeEmail(row.email());
                teacher = teachers.computeIfAbsent(email, Teacher::new);
            } catch (BadRequestException e) {
                errors.add(issue(row.row(), 0, "INVALID_EMAIL", e.getMessage()));
                // Still report independent cell errors; this row has no usable identity for batching.
                teacher = new Teacher(null);
            }
            teacher.rows.add(row.row());
            readName(row, teacher, errors);
            readSubject(row, teacher, catalog, errors);
            readHomeroom(row, teacher, catalog, errors);
        }
        Map<String, Entry> existing = new HashMap<>();
        Map<Long, String> owners = new HashMap<>();
        for (Entry entry : roster) {
            existing.put(entry.authEmail(), entry);
            // An omitted teacher keeps their homeroom; included teachers may exchange rooms.
            if (!teachers.containsKey(entry.authEmail()) && entry.term().homeroomClass() != null)
                owners.put(entry.term().homeroomClass().classId(), entry.authEmail());
        }
        for (var teacher : teachers.values()) {
            if (teacher.emptySubjectRow != 0 && teacher.rows.size() > 1) {
                errors.add(issue(teacher.emptySubjectRow, 2, "AMBIGUOUS_UNASSIGNED",
                        "미배정은 과목 코드를 비운 한 행으로만 입력하세요."));
            }
            if (teacher.homeroom != null && owners.putIfAbsent(teacher.homeroom.classId(), teacher.email) != null) {
                errors.add(issue(teacher.homeroomRow, 3, "HOMEROOM_OCCUPIED", "이 학기 학급에 다른 담임이 있습니다."));
            }
        }
        Set<Integer> invalidRows = new HashSet<>();
        errors.forEach(e -> invalidRows.add(e.row()));
        List<Change> changes = new ArrayList<>();
        for (var teacher : teachers.values()) {
            if (teacher.rows.stream().anyMatch(invalidRows::contains)) continue;
            Entry before = existing.get(teacher.email);
            Target after = new Target(teacher.name, List.copyOf(teacher.subjects.values()), teacher.homeroom);
            Action action = Action.ADD;
            if (before != null) {
                var previous = new Target(before.displayName(), before.term().subjects(), before.term().homeroomClass());
                action = before.term().assignmentRecorded() && previous.equals(after)
                        ? Action.UNCHANGED : Action.UPDATE;
            }
            changes.add(new Change(teacher.email, teacher.rows, action, before, after));
        }
        errors.sort(Comparator.comparing(Issue::row).thenComparing(Issue::column));
        return new Result(changes, errors);
    }

    private static void readName(RosterWorkbook.RowInput row, Teacher teacher, List<Issue> errors) {
        try {
            String name = TeacherRosterService.validateDisplayName(row.name());
            if (teacher.name != null && !teacher.name.equals(name))
                errors.add(issue(row.row(), 1, "CONFLICTING_NAME", "같은 이메일에 다른 표시 이름이 있습니다."));
            else teacher.name = name;
        } catch (BadRequestException e) {
            errors.add(issue(row.row(), 1, "INVALID_NAME", e.getMessage()));
        }
    }

    private static void readSubject(RosterWorkbook.RowInput row, Teacher teacher, Catalog catalog, List<Issue> errors) {
        if (row.subject().isEmpty()) {
            teacher.emptySubjectRow = row.row();
            return;
        }
        var subject = catalog.subjects().get(code(row.subject()));
        if (subject == null)
            errors.add(issue(row.row(), 2, "UNKNOWN_SUBJECT", "이 학교의 코드표에서 과목 코드를 확인하세요."));
        else if (teacher.subjects.putIfAbsent(subject.subjectId(), subject) != null)
            errors.add(issue(row.row(), 2, "DUPLICATE_SUBJECT", "같은 교사의 과목 코드가 중복되었습니다."));
    }

    private static void readHomeroom(RosterWorkbook.RowInput row, Teacher teacher, Catalog catalog, List<Issue> errors) {
        if (row.homeroom().isEmpty()) return;
        var room = catalog.classes().get(code(row.homeroom()));
        if (room == null)
            errors.add(issue(row.row(), 3, "UNKNOWN_CLASS", "이 학교·학년도의 학급 코드를 확인하세요."));
        else if (teacher.homeroom != null && !teacher.homeroom.equals(room))
            errors.add(issue(row.row(), 3, "CONFLICTING_HOMEROOM", "같은 교사에게 서로 다른 담임 학급이 있습니다."));
        else {
            teacher.homeroom = room;
            teacher.homeroomRow = row.row();
        }
    }

    // Excel can serialize an integral numeric cell as e.g. 543001.0.
    private static long code(String value) {
        if (!value.matches("[1-9][0-9]{0,18}(?:\\.0+)?")) return -1L;
        int dot = value.indexOf('.');
        try {
            return Long.parseLong(dot < 0 ? value : value.substring(0, dot));
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }
}
