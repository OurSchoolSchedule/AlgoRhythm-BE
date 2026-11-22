package com.rssolplan.edu.domain.schedule.loadimport;

import static com.rssolplan.edu.domain.schedule.loadimport.LoadProblem.Issue;

import org.apache.poi.ss.util.CellAddress;

import java.math.BigDecimal;
import java.util.*;

public final class LoadWorkbookParser {
    public record Demand(
            String teacherId,
            String subjectId,
            String classId,
            int weeklyPeriods,
            String sourceCell) {}

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Result(
            String sheet,
            int dataRows,
            long totalPeriods,
            List<Demand> demands,
            UUID validatedLoadId) {
        Result(String sheet, int dataRows, long totalPeriods, List<Demand> demands) {
            this(sheet, dataRows, totalPeriods, demands, null);
        }
    }

    private record Identity(long teacher, long subject, long schoolClass) {}

    Result parse(byte[] bytes, String sheet, LoadCatalog catalog) {
        LoadSheet s = LoadSheet.read(bytes, sheet);
        LoadSheetLayout h = LoadSheetLayout.detect(s);
        List<Issue> issues = new ArrayList<>();
        List<Demand> demands = new ArrayList<>();
        Map<Identity, String> firstCell = new HashMap<>();
        Map<Integer, Long> classIds = new TreeMap<>();
        h.classes()
                .forEach(
                        (c, key) -> {
                            Long id = catalog.classes().get(key);
                            if (id == null)
                                add(
                                        issues,
                                        Issue.at(
                                                "UNKNOWN_CLASS",
                                                s.name,
                                                h.firstDataRow() - 1,
                                                c,
                                                "학년도에 등록된 학급을 확인하세요."));
                            else classIds.put(c, id);
                        });
        long totalPeriods = 0;
        int dataRows = 0;
        for (var entry : s.rows.entrySet()) {
            int r = entry.getKey();
            if (r < h.firstDataRow()) continue;
            if (++dataRows > 5000)
                throw LoadProblem.file(
                        org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                        "ROW_LIMIT",
                        "데이터는 5,000행까지 지원합니다.");
            for (int c : entry.getValue().keySet()) {
                if (!h.known().contains(c))
                    add(
                            issues,
                            Issue.at("MISSING_COLUMN_HEADER", s.name, r, c, "값이 있는 열의 머리글이 없습니다."));
            }
            Long teacher = resolve(catalog.teachers(), s, r, h.teacher(), -1, "TEACHER", issues);
            Long subject =
                    resolve(
                            catalog.subjects(),
                            s,
                            r,
                            h.subject(),
                            h.shortName(),
                            "SUBJECT",
                            issues);
            int sum = 0;
            boolean validSum = true;
            for (int c : h.classes().keySet()) {
                Integer n = number(s, r, c, false, issues);
                if (n == null) {
                    validSum = false;
                    continue;
                }
                sum += n;
                Long classId = classIds.get(c);
                if (n == 0 || teacher == null || subject == null || classId == null) continue;
                String cell = new CellAddress(r, c).formatAsString();
                String previous =
                        firstCell.putIfAbsent(new Identity(teacher, subject, classId), cell);
                if (previous != null)
                    add(
                            issues,
                            Issue.at("DUPLICATE_DEMAND", s.name, r, c, "동일 배당의 첫 위치: " + previous));
                else
                    demands.add(
                            new Demand(
                                    teacher.toString(),
                                    subject.toString(),
                                    classId.toString(),
                                    n,
                                    cell));
            }
            Integer expected = number(s, r, h.total(), true, issues);
            if (expected != null && validSum && expected != sum)
                add(
                        issues,
                        Issue.at(
                                "ROW_TOTAL_MISMATCH",
                                s.name,
                                r,
                                h.total(),
                                "학급별 합은 " + sum + ", 입력한 계는 " + expected + "입니다."));
            totalPeriods += sum;
        }
        if (dataRows == 0 || (issues.isEmpty() && demands.isEmpty()))
            add(
                    issues,
                    Issue.at(
                            "EMPTY_DEMAND",
                            s.name,
                            h.firstDataRow(),
                            h.subject(),
                            "양수인 배당 시수가 없습니다."));
        if (!issues.isEmpty()) throw LoadProblem.invalid(issues);
        return new Result(s.name, dataRows, totalPeriods, List.copyOf(demands));
    }

    private Long resolve(
            LoadCatalog.Names names,
            LoadSheet s,
            int r,
            int col,
            int alt,
            String kind,
            List<Issue> issues) {
        LoadSheet.Value v = s.at(r, col);
        Set<Long> ids = new HashSet<>(names.matching(v.text()));
        if (alt >= 0) ids.addAll(names.matching(s.at(r, alt).text()));
        if (ids.size() == 1 && !v.invalid() && !v.text().isBlank()) return ids.iterator().next();
        String code = ids.size() > 1 ? "AMBIGUOUS_" + kind : "UNKNOWN_" + kind;
        add(issues, Issue.at(code, s.name, r, col, "학교 명부와 표기를 대조하세요. 동명이인·모호한 과목은 자동 배정하지 않습니다."));
        return null;
    }

    private Integer number(LoadSheet s, int r, int c, boolean total, List<Issue> issues) {
        CellAddress address = new CellAddress(r, c);
        if (s.merged.containsKey(address)) {
            add(issues, Issue.at("MERGED_HOURS", s.name, r, c, "시수·합계 셀의 병합을 해제하세요."));
            return null;
        }
        LoadSheet.Value v = s.raw(r, c);
        String t = java.text.Normalizer.normalize(v.text(), java.text.Normalizer.Form.NFKC).strip();
        if (!total && t.isEmpty() && !v.invalid()) return 0;
        if (!v.invalid() && t.length() <= 32 && t.matches("[0-9]+(?:\\.0+)?")) {
            try {
                int n = new BigDecimal(t).intValueExact();
                if (total || n <= 65535) return n;
            } catch (ArithmeticException ignored) {
                /* report the source cell below */
            }
        }
        add(issues, Issue.at("INVALID_HOURS", s.name, r, c, "0 이상의 정수가 필요합니다. 수식은 계산 결과를 저장하세요."));
        return null;
    }

    private static void add(List<Issue> issues, Issue issue) {
        if (issues.size() < 100) issues.add(issue);
    }
}
