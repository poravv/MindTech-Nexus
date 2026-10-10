package com.mindtechpy.nexus.infrastructure.persistence;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** Conversions between Java types and Postgres columns (timestamptz, uuid, jsonb). */
final class JdbcValues {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private JdbcValues() {
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static String json(JsonMapper mapper, Map<String, Object> value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    static Map<String, Object> map(JsonMapper mapper, String json) {
        return json == null ? null : mapper.readValue(json, MAP);
    }
}
