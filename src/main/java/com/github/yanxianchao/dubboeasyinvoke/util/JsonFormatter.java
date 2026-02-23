package com.github.yanxianchao.dubboeasyinvoke.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class JsonFormatter {

    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();

    private JsonFormatter() {
    }

    public static @NotNull String prettyIfJson(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }

        JsonNode jsonNode = readJsonOrNull(text);
        if (jsonNode == null) {
            return text;
        }

        try {
            return PRETTY_MAPPER.writeValueAsString(jsonNode);
        } catch (JsonProcessingException ignored) {
            return text;
        }
    }

    public static @NotNull String toDubboArgumentExpression(@Nullable String inputText) {
        if (inputText == null || inputText.isBlank()) {
            return "";
        }

        String trimmed = inputText.trim();
        JsonNode jsonNode = readJsonOrNull(trimmed);
        if (jsonNode == null) {
            return trimmed;
        }

        try {
            // 统一保留 JSON 根结构：对象/数组/标量都按紧凑 JSON 透传。
            // 这样像 [123] 这种 List/数组入参不会被拆成 123。
            return COMPACT_MAPPER.writeValueAsString(jsonNode);
        } catch (JsonProcessingException ignored) {
            return trimmed;
        }
    }

    public static @NotNull String prettyInvokeResult(@Nullable String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "";
        }

        String trimmed = rawResult.trim();
        JsonNode node = readJsonOrNull(trimmed);
        if (node != null) {
            try {
                return PRETTY_MAPPER.writeValueAsString(node);
            } catch (JsonProcessingException ignored) {
                return trimmed;
            }
        }

        return trimmed;
    }

    private static @Nullable JsonNode readJsonOrNull(@NotNull String text) {
        try {
            return COMPACT_MAPPER.readTree(text);
        } catch (Exception ignored) {
            return null;
        }
    }
}
