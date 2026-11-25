package com.rssolplan.edu.domain.neis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static com.rssolplan.edu.domain.neis.NeisClient.*;

class HttpNeisClientTest {
    HttpServer server;
    HttpNeisClient client;
    final AtomicInteger calls = new AtomicInteger();
    volatile int firstStatus, status;
    volatile String body, query, path;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub/", exchange -> {
            query = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            path = exchange.getRequestURI().getPath();
            int responseStatus = calls.incrementAndGet() == 1 ? firstStatus : status;
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        firstStatus = status = 200;
        client = new HttpNeisClient(new ObjectMapper(), true, "fixture",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hub/"));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void pageTwoKeepsTotalRowFieldsAndEncodedFilter() {
        body = """
                {"schoolInfo":[{"head":[{"list_total_count":2},{"RESULT":{"CODE":"INFO-000"}}]},
                {"row":[{"SCHUL_NM":"경신고등학교","SD_SCHUL_CODE":null}]}]}
                """;
        Page page = client.fetchPage(Dataset.SCHOOL, Map.of("SCHUL_NM", "경신고등학교"), 2, 1);
        assertThat(page.status()).isEqualTo(Status.OK);
        assertThat(page.totalCount()).isEqualTo(2);
        assertThat(page.rows().get(0)).containsEntry("SCHUL_NM","경신고등학교").containsEntry("SD_SCHUL_CODE",null);
        assertThat(query).contains("pIndex=2", "pSize=1", "SCHUL_NM=경신고등학교");
        assertThat(path).isEqualTo("/hub/schoolInfo");
    }

    @Test void noDataAndTransientRetry() {
        firstStatus = 503;
        body = "{\"RESULT\":{\"CODE\":\"INFO-200\"}}";
        assertThat(client.fetchPage(Dataset.MEAL, Map.of(), 1, 100))
                .isEqualTo(new Page(Status.NO_DATA,1,100,0,java.util.List.of()));
        assertThat(path).isEqualTo("/hub/mealServiceDietInfo");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void permanentErrorsMalformedAndShortPagesNeverBecomeEmptySuccess() {
        for (String invalid : new String[]{"ERROR-290","ERROR-337","INFO-300"}) {
            body = "{\"RESULT\":{\"CODE\":\"" + invalid + "\"}}";
            fails();
        }
        body = "{";
        fails();
        body = "{\"schoolInfo\":[{\"head\":[{\"list_total_count\":3},{\"RESULT\":{\"CODE\":\"INFO-000\"}}]},{\"row\":[]}]}";
        fails();
        firstStatus = status = 429;
        fails();
        assertThat(calls.get()).isEqualTo(6);
    }

    @Test void missingKeyAndDisabledFlagMakeNoRequest() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hub/");
        for (var unavailable : new HttpNeisClient[]{
                new HttpNeisClient(new ObjectMapper(), true, "", base),
                new HttpNeisClient(new ObjectMapper(), false, "fixture", base)}) {
            assertThatThrownBy(() -> unavailable.fetchPage(Dataset.SCHOOL, Map.of(), 1, 1))
                    .isInstanceOf(Unavailable.class);
        }
        assertThat(calls.get()).isZero();
    }

    private void fails() {
        assertThatThrownBy(() -> client.fetchPage(Dataset.SCHOOL, Map.of(), 1, 2))
                .isInstanceOf(Unavailable.class).hasNoCause().hasMessageNotContaining("fixture");
    }
}
