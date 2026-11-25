package com.rssolplan.edu.domain.neis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
class NeisSchoolService {
    private final SchoolLinkStore store;
    private final NeisClient client;
    private final SchoolSearchCache cache;
    private final boolean enabled;

    NeisSchoolService(SchoolLinkStore store, NeisClient client, SchoolSearchCache cache,
                      @Value("${neis.enabled}") boolean enabled) {
        this.store = store;
        this.client = client;
        this.cache = cache;
        this.enabled = enabled;
    }

    SchoolSearch search(long userId, String query, String office, String kind, int page, int size) {
        store.requireSearch(userId);
        String q = SchoolOption.clean(query);
        office = SchoolOption.clean(office);
        kind = SchoolOption.clean(kind);
        if (q == null || q.length() < 2 || q.length() > 100 || page < 1 || size < 1 || size > 50
                || (office != null && !office.matches("[A-Za-z0-9]{1,16}")) || (kind != null && kind.length() > 100))
            throw new IllegalArgumentException("학교 검색 조건과 페이지 범위를 확인하세요.");
        if (!enabled) throw new NeisClient.Unavailable();
        cache.count(userId);
        String key = cache.key(q, office, kind, page, size);
        SchoolSearch cached = cache.get(key);
        if (cached != null) return cached;

        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("SCHUL_NM", q);
        if (office != null) filters.put("ATPT_OFCDC_SC_CODE", office);
        if (kind != null) filters.put("SCHUL_KND_SC_NM", kind);
        var raw = client.fetchPage(NeisClient.Dataset.SCHOOL, filters, page, size);
        var result = new SchoolSearch(raw.rows().stream().map(NeisSchoolService::option).toList(),
                page, size, raw.totalCount(), (long) page * size < raw.totalCount());
        cache.put(key, result);
        return result;
    }

    SchoolLink change(long userId, long schoolId, LinkChange change) {
        SchoolLink current = store.inspect(userId, schoolId);
        if (current.matches(change.officeCode(), change.standardSchoolCode()))
            return store.save(userId, schoolId, change, null);
        if (current.version() != change.expectedVersion()) throw NeisSchoolException.conflict();
        if (!enabled) throw new NeisClient.Unavailable();
        var filters = Map.of("ATPT_OFCDC_SC_CODE", change.officeCode(), "SD_SCHUL_CODE", change.standardSchoolCode());
        var raw = client.fetchPage(NeisClient.Dataset.SCHOOL, filters, 1, 2);
        if (raw.totalCount() == 0)
            throw new NeisSchoolException(HttpStatus.NOT_FOUND, "NEIS_SCHOOL_NOT_FOUND", "학교 코드를 확인할 수 없습니다.");
        if (raw.totalCount() != 1 || raw.rows().size() != 1) throw new NeisClient.Unavailable();
        var next = option(raw.rows().get(0));
        if (!change.officeCode().equals(next.officeCode()) || !change.standardSchoolCode().equals(next.standardSchoolCode()))
            throw new NeisClient.Unavailable();
        return store.save(userId, schoolId, change, next);
    }

    private static SchoolOption option(Map<String, String> row) {
        return SchoolOption.of(row.get("ATPT_OFCDC_SC_CODE"), row.get("SD_SCHUL_CODE"),
                row.get("ATPT_OFCDC_SC_NM"), row.get("SCHUL_NM"), row.get("SCHUL_KND_SC_NM"), row.get("ORG_RDNMA"));
    }
}
