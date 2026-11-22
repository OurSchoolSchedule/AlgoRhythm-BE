package com.rssolplan.edu.domain.schedule.loadimport;

import static com.rssolplan.edu.domain.schedule.loadimport.LoadProblem.Issue;

import java.util.*;
import java.util.regex.*;

record LoadSheetLayout(
        int firstDataRow,
        int teacher,
        int subject,
        int shortName,
        int total,
        Map<Integer, LoadCatalog.ClassKey> classes,
        Set<Integer> known) {
    private static final Pattern CLASS = Pattern.compile("(\\d{1,2})(?:학년|-)(\\d{1,2})반?");
    private static final Pattern GRADE = Pattern.compile("(\\d{1,2})학년");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,2})반?");

    static String label(LoadSheet s, int r, int c) {
        return LoadCatalog.key(s.at(r, c).text());
    }

    static LoadSheetLayout detect(LoadSheet s) {
        for (int r : s.rows.keySet()) {
            if (r >= 30) break;
            if (s.rows.get(r).values().stream()
                    .noneMatch(v -> teacherLabel(LoadCatalog.key(v.text())))) continue;
            int teacher = -1, subject = -1, shortName = -1, total = -1;
            boolean twoLines = false;
            Map<Integer, LoadCatalog.ClassKey> classes = new TreeMap<>();
            Set<Integer> known = new HashSet<>();
            Set<LoadCatalog.ClassKey> seen = new HashSet<>();
            for (int c = 0; c < LoadSheet.MAX_COLS; c++) {
                String h = label(s, r, c);
                if (h.isEmpty()) continue;
                if (teacherLabel(h)) {
                    ensureNew(s, r, c, teacher);
                    teacher = c;
                } else if (Set.of("과목", "과목명", "정식과목명", "교과목명").contains(h)) {
                    ensureNew(s, r, c, subject);
                    subject = c;
                } else if (Set.of("단축과목명", "약칭", "단축명").contains(h)) {
                    ensureNew(s, r, c, shortName);
                    shortName = c;
                } else if (Set.of("계", "합계", "주당시수합계", "총시수", "주당시수").contains(h)) {
                    ensureNew(s, r, c, total);
                    total = c;
                } else if (!Set.of("순", "순번", "번호", "비고").contains(h)) {
                    Matcher full = CLASS.matcher(h), grade = GRADE.matcher(h);
                    LoadCatalog.ClassKey k;
                    if (full.matches())
                        k =
                                new LoadCatalog.ClassKey(
                                        Integer.parseInt(full.group(1)),
                                        Integer.parseInt(full.group(2)));
                    else if (grade.matches()) {
                        Matcher num = NUMBER.matcher(label(s, r + 1, c));
                        if (!num.matches())
                            throw bad(s, r + 1, c, "CLASS_HEADER", "학년 아래에 반 번호가 필요합니다.");
                        k =
                                new LoadCatalog.ClassKey(
                                        Integer.parseInt(grade.group(1)),
                                        Integer.parseInt(num.group(1)));
                        twoLines = true;
                    } else {
                        Matcher upper = GRADE.matcher(r == 0 ? "" : label(s, r - 1, c));
                        Matcher lower = NUMBER.matcher(h);
                        if (!upper.matches() || !lower.matches())
                            throw bad(s, r, c, "UNKNOWN_COLUMN", "열 이름 또는 학년·반 표기를 확인하세요.");
                        k =
                                new LoadCatalog.ClassKey(
                                        Integer.parseInt(upper.group(1)),
                                        Integer.parseInt(lower.group(1)));
                    }
                    if (k.grade() < 1 || k.number() < 1 || !seen.add(k))
                        throw bad(s, r, c, "CLASS_HEADER", "학급이 중복되거나 번호가 잘못되었습니다.");
                    classes.put(c, k);
                }
                known.add(c);
            }
            if (subject < 0 || total < 0 || classes.isEmpty())
                throw bad(s, r, teacher, "MISSING_HEADER", "과목명, 학년·반, 계 열이 필요합니다.");
            return new LoadSheetLayout(
                    r + (twoLines ? 2 : 1), teacher, subject, shortName, total, classes, known);
        }
        throw bad(s, 0, 0, "MISSING_HEADER", "앞 30행에서 교사명·과목명·학급·계 머리글을 찾지 못했습니다.");
    }

    private static boolean teacherLabel(String v) {
        return Set.of("교사", "교사명", "성명", "담당교사", "교사성명").contains(v);
    }

    private static void ensureNew(LoadSheet s, int r, int c, int previous) {
        if (previous >= 0) throw bad(s, r, c, "DUPLICATE_HEADER", "동일한 머리글이 두 번 나타났습니다.");
    }

    private static LoadProblem bad(LoadSheet s, int r, int c, String code, String message) {
        return LoadProblem.invalid(List.of(Issue.at(code, s.name, r, c, message)));
    }
}
