package com.rssolplan.edu.domain.neis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
class HttpNeisClient implements NeisClient {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String key;
    private final URI base;

    @Autowired
    HttpNeisClient(ObjectMapper json, @Value("${neis.enabled}") boolean enabled, @Value("${neis.api-key}") String key) {
        this(json, enabled, key, URI.create("https://open.neis.go.kr/hub/"));
    }

    HttpNeisClient(ObjectMapper json, boolean enabled, String key, URI base) {
        this.json = json;
        this.enabled = enabled;
        this.key = key;
        this.base = base;
    }

    @Override
    public Page fetchPage(Dataset dataset, Map<String, String> filters, int page, int size) {
        if (!enabled || key.isBlank()) throw new Unavailable();
        if (page < 1 || size < 1 || size > 100 || !dataset.filters.containsAll(filters.keySet())) {
            throw new IllegalArgumentException("잘못된 조회 조건입니다.");
        }
        var uri = UriComponentsBuilder.fromUri(base.resolve(dataset.path));
        var params = new LinkedHashMap<String, Object>(filters);
        params.put("KEY", key);
        params.put("Type", "json");
        params.put("pIndex", page);
        params.put("pSize", size);
        params.keySet().forEach(k -> uri.queryParam(k, "{" + k + "}"));
        URI target = uri.encode(StandardCharsets.UTF_8).buildAndExpand(params).toUri();
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) target.toURL().openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                int status = connection.getResponseCode();
                if (attempt == 0 && (status == 502 || status == 503 || status == 504)) continue;
                if (status != 200) throw new Unavailable();
                byte[] body;
                try (var in = connection.getInputStream()) {
                    body = in.readNBytes(MAX_BYTES + 1);
                }
                if (body.length > MAX_BYTES) throw new Unavailable();
                return decode(body, dataset, page, size);
            } catch (IOException ex) {
                if (attempt == 1) throw new Unavailable();
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new Unavailable();
    }

    private Page decode(byte[] body, Dataset dataset, int page, int size) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException invalid) {
            throw new Unavailable();
        }
        if (root == null) throw new Unavailable();
        if (root.has("RESULT")) {
            if ("INFO-200".equals(root.path("RESULT").path("CODE").asText()))
                return new Page(Status.NO_DATA, page, size, 0, List.of());
            throw new Unavailable();
        }
        JsonNode blocks = root.path(dataset.path);
        if (!blocks.isArray()) throw new Unavailable();
        JsonNode head = null, rows = null;
        for (JsonNode block : blocks) {
            if (block.has("head")) head = block.get("head");
            if (block.has("row")) rows = block.get("row");
        }
        if (head == null || !head.isArray() || rows == null || !rows.isArray()) throw new Unavailable();
        int total = -1;
        String code = null;
        for (JsonNode item : head) {
            if (item.has("list_total_count") && item.get("list_total_count").isIntegralNumber()
                    && item.get("list_total_count").canConvertToInt()) total = item.get("list_total_count").intValue();
            if (item.has("RESULT")) code = item.path("RESULT").path("CODE").asText();
        }
        long remaining = Math.max(0L, total - (long) (page - 1) * size);
        if (!"INFO-000".equals(code) || total < 0 || rows.size() != Math.min(size, remaining))
            throw new Unavailable();
        List<Map<String, String>> result = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject() || row.isEmpty()) throw new Unavailable();
            var fields = new LinkedHashMap<String, String>();
            row.fields().forEachRemaining(field -> {
                JsonNode value = field.getValue();
                if (value.isContainerNode()) throw new Unavailable();
                fields.put(field.getKey(), value.isNull() ? null : value.asText());
            });
            result.add(Collections.unmodifiableMap(fields));
        }
        return new Page(Status.OK, page, size, total, result);
    }
}
