package com.mindtechpy.nexus.domain;

import com.mindtechpy.nexus.domain.connection.BaseUrlPolicy;
import com.mindtechpy.nexus.domain.shared.DomainException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaseUrlPolicyTest {

    private final BaseUrlPolicy policy = new BaseUrlPolicy(List.of("*.svc.cluster.local", "localhost"));

    @ParameterizedTest
    @ValueSource(strings = {
            "http://backend.sifen.svc.cluster.local:8081",
            "http://backend.punto-venta.svc.cluster.local:3000/api/",
            "https://LOCALHOST:8443"})
    void should_accept_when_host_is_allowlisted(String url) {
        assertThat(policy.validate(url)).doesNotEndWith("/");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example.com",
            "http://svc.cluster.local.evil.com",
            "http://10.0.0.5:8080",
            "http://[::1]:8080",
            "http://user:pass@backend.sifen.svc.cluster.local",
            "ftp://backend.sifen.svc.cluster.local",
            "http://backend.sifen.svc.cluster.local?x=1",
            "not a url",
            ""})
    void should_reject_when_url_is_unsafe(String url) {
        assertThatThrownBy(() -> policy.validate(url))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "invalid-base-url");
    }
}
