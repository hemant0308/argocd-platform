package com.argocd.platform.api.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

/**
 * Centralised JSONB serialisation/deserialisation utilities.
 *
 * <p>All repositories and services that need to convert between Java objects and jOOQ
 * {@link JSONB} values should inject this component instead of duplicating the logic.
 *
 * <p>Uses the Spring-managed {@link ObjectMapper} so that any Jackson modules or
 * configuration applied at startup (e.g. JavaTimeModule, visibility settings) are
 * respected consistently across the application.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonbUtils {

    private final ObjectMapper objectMapper;

    /**
     * Serialises an arbitrary Java object to a jOOQ {@link JSONB} value.
     *
     * @param value the object to serialise; {@code null} returns {@code null}
     * @return a {@link JSONB} wrapping the JSON string, or {@code null} if {@code value} is {@code null}
     * @throws IllegalStateException if Jackson serialisation fails
     */
    public JSONB toJsonb(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSONB.jsonb(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize value to JSONB: " + e.getMessage(), e);
        }
    }

    /**
     * Deserialises a jOOQ {@link JSONB} column value into the given target type.
     *
     * <p>Returns {@code null} when {@code jsonb} is {@code null} or its data is blank.
     * Callers that need an empty collection instead of {@code null} should wrap the call:
     * <pre>{@code
     *   Objects.requireNonNullElse(jsonbUtils.fromJsonb(jsonb, TYPE_REF), List.of())
     * }</pre>
     *
     * @param jsonb   the JSONB value from a jOOQ record; may be {@code null}
     * @param typeRef Jackson {@link TypeReference} describing the target type
     * @param <T>     the target type
     * @return the deserialised value, or {@code null} if the input is absent/blank
     */
    public <T> T fromJsonb(JSONB jsonb, TypeReference<T> typeRef) {
        if (jsonb == null || jsonb.data() == null || jsonb.data().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonb.data(), typeRef);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize JSONB value '{}': {}", jsonb.data(), e.getMessage());
            return null;
        }
    }
}
