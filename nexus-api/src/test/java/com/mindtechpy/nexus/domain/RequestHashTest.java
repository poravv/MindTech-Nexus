package com.mindtechpy.nexus.domain;

import com.mindtechpy.nexus.domain.operation.RequestHash;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHashTest {

    @Test
    void should_hash_equal_when_key_order_differs_or_null_members_are_present() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("sourceRef", "abc");
        first.put("items", List.of(Map.of("code", "PAN-001", "quantity", "4")));
        first.put("customer", null);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("items", List.of(Map.of("quantity", "4", "code", "PAN-001")));
        second.put("sourceRef", "abc");

        assertThat(RequestHash.of(first)).isEqualTo(RequestHash.of(second)).hasSize(64);
    }

    @Test
    void should_hash_equal_when_numbers_only_differ_in_representation() {
        assertThat(RequestHash.of(Map.of("total", 9100))).isEqualTo(RequestHash.of(Map.of("total", 9100L)))
                .isEqualTo(RequestHash.of(Map.of("total", new BigDecimal("9100.00"))));
    }

    @Test
    void should_hash_differently_when_content_or_list_order_changes() {
        assertThat(RequestHash.of(Map.of("total", 9100))).isNotEqualTo(RequestHash.of(Map.of("total", 9101)));
        assertThat(RequestHash.of(Map.of("items", List.of("a", "b")))).isNotEqualTo(RequestHash.of(Map.of("items", List.of("b", "a"))));
        assertThat(RequestHash.of(Map.of("v", "1"))).isNotEqualTo(RequestHash.of(Map.of("v", 1)));
    }

    @Test
    void should_keep_null_list_elements_when_hashing() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("items", Arrays.asList("a", null));

        assertThat(RequestHash.of(withNull)).isNotEqualTo(RequestHash.of(Map.of("items", List.of("a"))));
    }
}
