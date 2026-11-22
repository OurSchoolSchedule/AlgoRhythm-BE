package com.rssolplan.edu.domain.schedule.loadimport;

import java.text.Normalizer;
import java.util.*;

record LoadCatalog(Names teachers, Names subjects, Map<ClassKey, Long> classes) {
    record ClassKey(int grade, int number) {}

    record Name(long id, String label) {}

    static String key(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    static final class Names {
        private final Map<String, Set<Long>> ids = new HashMap<>();

        Names(List<Name> entries) {
            for (Name entry : entries) {
                String key = key(entry.label());
                if (!key.isEmpty())
                    ids.computeIfAbsent(key, ignored -> new HashSet<>()).add(entry.id());
            }
        }

        Set<Long> matching(String name) {
            return ids.getOrDefault(key(name), Set.of());
        }

        void addAliases(Map<String, Long> aliases, Set<Long> schoolIds) {
            aliases.forEach(
                    (label, id) -> {
                        String normalized = key(label);
                        if (normalized.isEmpty() || id == null || !schoolIds.contains(id))
                            throw LoadProblem.file(
                                    org.springframework.http.HttpStatus.BAD_REQUEST,
                                    "INVALID_ALIAS",
                                    "과목 별칭은 현재 학교의 과목 ID와 연결해야 합니다.");
                        Set<Long> matches =
                                ids.computeIfAbsent(normalized, ignored -> new HashSet<>());
                        if (!matches.isEmpty() && !matches.equals(Set.of(id)))
                            throw LoadProblem.file(
                                    org.springframework.http.HttpStatus.BAD_REQUEST,
                                    "ALIAS_CONFLICT",
                                    "별칭이 기존 과목 또는 다른 별칭과 충돌합니다.");
                        matches.add(id);
                    });
        }
    }
}
