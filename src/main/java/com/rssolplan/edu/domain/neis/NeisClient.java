package com.rssolplan.edu.domain.neis;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One provider request page. Filters contain provider field names, never credentials, host or pagination. */
public interface NeisClient {
    Page fetchPage(Dataset dataset, Map<String, String> filters, int page, int size);

    enum Dataset {
        SCHOOL("schoolInfo", Set.of("SCHUL_NM", "ATPT_OFCDC_SC_CODE", "SD_SCHUL_CODE", "SCHUL_KND_SC_NM")),
        MEAL("mealServiceDietInfo", Set.of("ATPT_OFCDC_SC_CODE", "SD_SCHUL_CODE", "MLSV_YMD",
                "MLSV_FROM_YMD", "MLSV_TO_YMD", "MMEAL_SC_CODE"));

        final String path;
        final Set<String> filters;
        Dataset(String path, Set<String> filters) {
            this.path = path;
            this.filters = filters;
        }
    }

    enum Status { OK, NO_DATA }

    /** INFO-200 is NO_DATA (totalCount=0, rows empty). Other provider errors throw Unavailable.
     * OK carries only this page of raw string fields (missing/null fields are possible).
     * Consumers fetching a range must verify total count, continuity and duplicate pages. */
    record Page(Status status, int page, int size, int totalCount, List<Map<String, String>> rows) {
        public Page {
            rows = List.copyOf(rows);
        }
    }

    /** Never includes a request URL, secret, provider body or transport exception. */
    class Unavailable extends RuntimeException {
        public Unavailable() { super("나이스 정보를 일시적으로 조회할 수 없습니다."); }
    }
}
