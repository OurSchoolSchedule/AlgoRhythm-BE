package com.rssolplan.edu.domain.school.homeroom;

import com.rssolplan.edu.domain.schedule.loadimport.LoadProblem.Issue;
import com.rssolplan.edu.domain.schedule.loadimport.LoadSheet;

import java.math.BigDecimal;
import java.util.*;

final class HomeroomWorkbook {
    static final String SHEET = "학급담당";
    static final List<String> HEADER = List.of("학년", "반", "학급 ID", "담임 교사 ID", "담임 이름", "부담임 교사 ID", "부담임 이름");
    record Room(long id, int grade, int number) {}
    record Pair(long homeroom, long deputy) {}
    static final class Invalid extends RuntimeException {
        final List<Issue> issues;
        Invalid(List<Issue> issues) {
            super("학급담당 시트의 표시된 값을 확인하세요.");
            this.issues = List.copyOf(issues);
        }
    }

    static Map<Long, Pair> parse(byte[] bytes, Map<Long, Room> rooms, Map<Long,String> teachers) {
        var table = LoadSheet.readTable(bytes, SHEET);
        var errors = new ArrayList<Issue>();
        var header = table.getOrDefault(0, Map.of());
        for (int c = 0; c < HEADER.size(); c++)
            if (!HEADER.get(c).equals(value(header, c))) error(errors, 0, c, "INVALID_HEADER", "머리글: " + HEADER.get(c));

        Map<Long,Pair> pairs = new TreeMap<>();
        Set<Long> assigned = new HashSet<>();
        for (var entry : table.entrySet()) {
            int row = entry.getKey();
            var cells = entry.getValue();
            cells.forEach((col, cell) -> {
                if (col >= HEADER.size()) error(errors, row, col, "EXTRA_COLUMN", "양식에 없는 열입니다.");
                else if (cell.invalid()) error(errors, row, col, "INVALID_CELL", "수식·날짜 대신 값을 입력하세요.");
            });
            if (row == 0) continue;
            long classId = number(cells, 2);
            Room room = rooms.get(classId);
            if (room == null || number(cells, 0) != room.grade() || number(cells, 1) != room.number())
                error(errors, row, 2, "UNKNOWN_CLASS", "선택한 학기의 학년·반·학급 ID가 일치해야 합니다.");
            long primary = teacher(cells, row, 3, teachers, assigned, errors);
            long deputy = teacher(cells, row, 5, teachers, assigned, errors);
            if (pairs.putIfAbsent(classId, new Pair(primary, deputy)) != null)
                error(errors, row, 2, "DUPLICATE_CLASS", "같은 학급을 두 번 입력했습니다.");
        }
        for (var room : rooms.values()) {
            if (!pairs.containsKey(room.id())) error(errors, 0, 2, "MISSING_CLASS",
                    room.grade() + "학년 " + room.number() + "반의 담임·부담임이 필요합니다.");
        }
        if (rooms.isEmpty()) error(errors, 0, 2, "EMPTY_CLASSES", "선택한 학년도에 학급이 없습니다.");
        if (!errors.isEmpty()) throw new Invalid(errors);
        return pairs;
    }

    private static long teacher(Map<Integer,LoadSheet.Value> cells, int row, int col, Map<Long,String> teachers,
                                Set<Long> assigned, List<Issue> errors) {
        long id = number(cells, col);
        if (!teachers.containsKey(id))
            error(errors, row, col, "UNKNOWN_TEACHER", "현재 학교의 재직 교사 편성용 소속 ID를 입력하세요.");
        else if (!assigned.add(id))
            error(errors, row, col, "DUPLICATE_TEACHER", "한 교사는 이 학기에 하나의 학급 역할만 맡을 수 있습니다.");
        if (value(cells, col + 1).isBlank())
            error(errors, row, col + 1, "MISSING_NAME", "파일에서 확인할 교사 이름을 입력하세요.");
        return id;
    }

    private static long number(Map<Integer, LoadSheet.Value> cells, int col) {
        String value = value(cells, col);
        if (!value.matches("[1-9][0-9]{0,18}(?:\\.0+)?")) return -1;
        try { return new BigDecimal(value).longValueExact(); }
        catch (ArithmeticException e) { return -1; }
    }

    private static String value(Map<Integer,LoadSheet.Value> cells, int col) {
        var cell = cells.get(col);
        return cell == null ? "" : cell.text().strip();
    }

    private static void error(List<Issue> issues, int row, int col, String code, String message) {
        if (issues.size() < 100) issues.add(Issue.at(code, SHEET, row, col, message));
    }
}
