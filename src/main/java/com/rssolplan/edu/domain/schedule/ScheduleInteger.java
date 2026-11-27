package com.rssolplan.edu.domain.schedule;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Do not round a grade or period into a different registered identifier. */
public final class ScheduleInteger extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT))
            return context.reportInputMismatch(Integer.class, "학년과 교시 번호는 정수로 입력하세요.");
        return context.readValue(parser, Integer.class);
    }
}
