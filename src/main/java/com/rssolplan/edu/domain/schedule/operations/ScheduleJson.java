package com.rssolplan.edu.domain.schedule.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ScheduleJson {
    private final ObjectMapper mapper;

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("작업 자료를 저장할 수 없습니다.", e);
        }
    }

    public <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 작업 자료를 읽을 수 없습니다.", e);
        }
    }
}
