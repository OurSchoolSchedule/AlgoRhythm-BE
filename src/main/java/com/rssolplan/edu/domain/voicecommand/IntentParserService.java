package com.rssolplan.edu.domain.voicecommand;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rssolplan.edu.domain.voicecommand.dto.ParsedIntent;
import com.rssolplan.edu.global.config.OpenAiProperties;
import com.rssolplan.edu.global.exception.IntentParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class IntentParserService {

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    private final OpenAiProperties openAiProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public ParsedIntent parse(String text) {
        try {
            String requestBody = buildRequestBody(text);

            String responseJson = restClient.post()
                    .uri(OPENAI_URL)
                    .header("Authorization", "Bearer " + openAiProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return extractParsedIntent(responseJson);
        } catch (IntentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new IntentParseException("AI 파싱 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private String buildRequestBody(String userText) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", openAiProperties.getModel());

        // messages
        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", buildSystemPrompt());
        messages.addObject()
                .put("role", "user")
                .put("content", userText);

        // tools: parse_intent function
        ArrayNode tools = root.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", "parse_intent");
        function.put("description", "사용자의 자연어 입력에서 의도와 파라미터를 추출합니다.");
        buildParameterSchema(function.putObject("parameters"));

        // tool_choice: force the function call
        root.putObject("tool_choice")
                .put("type", "function")
                .putObject("function")
                .put("name", "parse_intent");

        return objectMapper.writeValueAsString(root);
    }

    private String buildSystemPrompt() {
        LocalDate today = LocalDate.now();
        String koreanDay = today.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        return String.format("""
                당신은 학교 행정 시스템의 명령 파서입니다.
                오늘 날짜: %s (%s)

                사용자의 자연어 입력을 분석하여 아래 parse_intent 함수의 파라미터를 정확히 추출하세요.

                지원 기능:
                - SUBSTITUTE_CREATE : 보결 교사 요청 생성 (교감/교장)
                - SUBSTITUTE_RESPOND: 보결 수락/거절 (교사, action: ACCEPT 또는 REJECT)
                - SUBSTITUTE_APPROVE: 보결 최종 승인 (교감/교장, action: APPROVE 또는 REJECT)
                - AVAILABILITY_ADD  : 불가 교시 추가 등록
                - AVAILABILITY_REPLACE: 불가 교시 전체 교체
                - ATTENDANCE_CHECK_IN : 출근 처리
                - ATTENDANCE_CHECK_OUT: 퇴근 처리
                - UNKNOWN: 위 기능에 해당하지 않는 경우

                규칙:
                - 날짜는 반드시 ISO-8601(YYYY-MM-DD) 형식으로 변환하세요.
                - 요일은 MON/TUE/WED/THU/FRI/SAT/SUN 으로 변환하세요.
                - 명확하지 않은 정보는 null로 남기세요.
                - confidence는 추출 신뢰도: HIGH(명확)/MEDIUM(일부 불명확)/LOW(추측)
                """, today, koreanDay);
    }

    private void buildParameterSchema(ObjectNode params) {
        params.put("type", "object");

        ObjectNode properties = params.putObject("properties");

        addEnumProp(properties, "intentType",
                new String[]{"SUBSTITUTE_CREATE", "SUBSTITUTE_RESPOND", "SUBSTITUTE_APPROVE",
                        "AVAILABILITY_ADD", "AVAILABILITY_REPLACE",
                        "ATTENDANCE_CHECK_IN", "ATTENDANCE_CHECK_OUT", "UNKNOWN"},
                "추출된 의도 유형");

        addEnumProp(properties, "confidence",
                new String[]{"HIGH", "MEDIUM", "LOW"},
                "추출 신뢰도");

        addNullableInt(properties, "timetableId", "SUBSTITUTE_CREATE: 시간표 ID");
        addNullableString(properties, "substituteDate", "SUBSTITUTE_CREATE: 보결 날짜 (YYYY-MM-DD)");
        addNullableString(properties, "note", "SUBSTITUTE_CREATE: 비고");
        addNullableInt(properties, "requestId", "SUBSTITUTE_RESPOND: 보결 요청 ID");
        addNullableInt(properties, "responseId", "SUBSTITUTE_APPROVE: 교사 응답 ID");
        addEnumPropNullable(properties, "action",
                new String[]{"ACCEPT", "REJECT", "APPROVE"},
                "수락/거절/승인 액션");

        // unavailabilitySlots array
        ObjectNode slots = properties.putObject("unavailabilitySlots");
        slots.put("type", "array");
        slots.putNull("nullable");
        slots.put("description", "불가 교시 목록");
        ObjectNode slotItem = slots.putObject("items");
        slotItem.put("type", "object");
        ObjectNode slotProps = slotItem.putObject("properties");
        addEnumProp(slotProps, "dayOfWeek",
                new String[]{"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"}, "요일");
        slotProps.putObject("periodNumber").put("type", "integer").put("description", "교시");
        slotProps.putObject("reason").put("type", "string");
        slotItem.putArray("required").add("dayOfWeek").add("periodNumber");

        params.putArray("required").add("intentType").add("confidence");
    }

    private void addEnumProp(ObjectNode parent, String name, String[] values, String description) {
        ObjectNode prop = parent.putObject(name);
        prop.put("type", "string");
        prop.put("description", description);
        ArrayNode en = prop.putArray("enum");
        for (String v : values) en.add(v);
    }

    private void addEnumPropNullable(ObjectNode parent, String name, String[] values, String description) {
        ObjectNode prop = parent.putObject(name);
        ArrayNode typeArr = prop.putArray("type");
        typeArr.add("string");
        typeArr.add("null");
        prop.put("description", description);
        ArrayNode en = prop.putArray("enum");
        for (String v : values) en.add(v);
        en.addNull();
    }

    private void addNullableString(ObjectNode parent, String name, String description) {
        ObjectNode prop = parent.putObject(name);
        ArrayNode typeArr = prop.putArray("type");
        typeArr.add("string");
        typeArr.add("null");
        prop.put("description", description);
    }

    private void addNullableInt(ObjectNode parent, String name, String description) {
        ObjectNode prop = parent.putObject(name);
        ArrayNode typeArr = prop.putArray("type");
        typeArr.add("integer");
        typeArr.add("null");
        prop.put("description", description);
    }

    private ParsedIntent extractParsedIntent(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String arguments = root
                    .path("choices").get(0)
                    .path("message")
                    .path("tool_calls").get(0)
                    .path("function")
                    .path("arguments")
                    .asText();

            ParsedIntent intent = objectMapper.readValue(arguments, ParsedIntent.class);
            if (intent.intentType() == null || intent.intentType().isBlank()) {
                throw new IntentParseException("AI가 의도를 인식하지 못했습니다.");
            }
            return intent;
        } catch (IntentParseException e) {
            throw e;
        } catch (Exception e) {
            throw new IntentParseException("AI 응답 파싱에 실패했습니다: " + e.getMessage());
        }
    }
}
