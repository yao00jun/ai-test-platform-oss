package com.aitest.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Jackson 2 stays inside this persistence/SDK boundary. REST uses Boot's Jackson 3. */
@Component
public final class JsonCodec {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot serialize value", e); }
    }
    public <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw Problem.invalid("JSON 格式或字段不正确: " + e.getOriginalMessage()); }
    }
    public Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (JsonProcessingException e) { throw Problem.invalid("JSON 必须是对象: " + e.getOriginalMessage()); }
    }
    public Object tree(String value) {
        try { return mapper.readValue(value, Object.class); }
        catch (JsonProcessingException e) { throw Problem.invalid("JSON 格式不正确: " + e.getOriginalMessage()); }
    }
    public <T> T convert(Object value, Class<T> type) {
        try { return mapper.convertValue(value, type); }
        catch (IllegalArgumentException e) { throw Problem.invalid("字段结构不正确: " + e.getMessage()); }
    }
    public Map<String, Object> copy(Map<String, Object> value) { return map(write(value)); }
}
