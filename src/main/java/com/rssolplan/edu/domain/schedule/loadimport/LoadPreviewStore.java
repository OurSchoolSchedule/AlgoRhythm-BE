package com.rssolplan.edu.domain.schedule.loadimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rssolplan.edu.global.exception.NotFoundException;

import lombok.RequiredArgsConstructor;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LoadPreviewStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    static final Duration TTL = Duration.ofMinutes(30);

    public static final class Unavailable extends RuntimeException {
        Unavailable(org.springframework.dao.DataAccessException cause) {
            super("검증본 임시 저장소에 접속할 수 없습니다. 잠시 후 다시 시도하세요.", cause);
        }
    }

    LoadWorkbookParser.Result retain(
            LoadCatalog scope, Long termId, LoadWorkbookParser.Result result) {
        UUID id = UUID.randomUUID();
        var preview =
                new LoadWorkbookParser.Result(
                        result.sheet(),
                        result.dataRows(),
                        result.totalPeriods(),
                        result.demands(),
                        id);
        try {
            redis.opsForValue().set(key(scope, termId, id), json.writeValueAsString(preview), TTL);
            return preview;
        } catch (org.springframework.dao.DataAccessException e) {
            throw new Unavailable(e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("검증 결과를 직렬화할 수 없습니다.", e);
        }
    }

    public LoadWorkbookParser.Result read(LoadCatalog scope, Long termId, UUID id) {
        String stored;
        try {
            var values = redis.opsForValue();
            stored = values.get(key(scope, termId, id));
            // A preview from school setup is valid for either semester of that school year.
            if (stored == null && termId != null) stored = values.get(key(scope, null, id));
        } catch (org.springframework.dao.DataAccessException e) {
            throw new Unavailable(e);
        }
        if (stored == null) throw new NotFoundException("이 학기에서 사용할 시수표가 없거나 만료되었습니다. 다시 검증하세요.");
        try {
            var preview = json.readValue(stored, LoadWorkbookParser.Result.class);
            Set<Long> classes = Set.copyOf(scope.classes().values());
            for (var row : preview.demands()) {
                if (!scope.hasTeacherAndSubject(row)
                        || !classes.contains(Long.valueOf(row.classId())))
                    throw new IllegalStateException(
                            row.sourceCell() + "의 교사·과목·학급 명부가 변경되었습니다. 다시 검증하세요.");
            }
            return preview;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("시수표를 다시 검증하세요.", e);
        }
    }

    private static String key(LoadCatalog scope, Long termId, UUID id) {
        return "schedule:load:v1:"
                + scope.schoolId()
                + ":"
                + scope.academicYear()
                + ":"
                + (termId == null ? "school" : termId)
                + ":"
                + id;
    }
}
