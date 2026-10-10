package com.mindtechpy.nexus.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.usecase.IssueClientToken;
import com.mindtechpy.nexus.application.usecase.IssueClientToken.ClientGrant;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.shared.DomainException;
import com.mindtechpy.nexus.infrastructure.security.HmacJwtSupport;
import com.mindtechpy.nexus.infrastructure.security.HmacJwtSupport.IssuedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;

@Tag(name = "OAuth")
@RestController
class OAuthTokenController {

    private static final Logger log = LoggerFactory.getLogger(OAuthTokenController.class);

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("token_type") String tokenType,
                         @JsonProperty("expires_in") long expiresIn,
                         String scope) {
    }

    private final IssueClientToken issueClientToken;
    private final HmacJwtSupport jwt;
    private final ClockPort clock;

    OAuthTokenController(IssueClientToken issueClientToken, HmacJwtSupport jwt, ClockPort clock) {
        this.issueClientToken = issueClientToken;
        this.jwt = jwt;
        this.clock = clock;
    }

    /**
     * client_credentials grant with HTTP Basic {@code client_id:client_secret}. Returns a 15-minute
     * HS256 token carrying {@code tenant_id} and the client's scopes.
     */
    @Operation(summary = "Emite un access token m2m (grant client_credentials, HTTP Basic)")
    @PostMapping(path = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    TokenResponse token(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                        @RequestParam("grant_type") String grantType) {
        if (!"client_credentials".equals(grantType)) {
            throw DomainException.invalid("unsupported-grant-type", "Solo se admite grant_type=client_credentials");
        }
        String[] basic = parseBasic(authorization);
        ClientGrant grant = issueClientToken.execute(basic[0], basic[1]);
        IssuedToken token = jwt.issueForClient(grant, clock.now());
        log.info("[NEXUS-AUTH] token m2m emitido client={} tenant={}", grant.clientId(), grant.tenantId());
        String scope = grant.scopes().stream().map(Scope::value).sorted().collect(Collectors.joining(" "));
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds(), scope);
    }

    private static String[] parseBasic(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw invalidClient();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalidClient();
        }
        int colon = decoded.indexOf(':');
        if (colon <= 0) {
            throw invalidClient();
        }
        return new String[]{decoded.substring(0, colon), decoded.substring(colon + 1)};
    }

    private static DomainException invalidClient() {
        return DomainException.unauthorized("invalid-client", "Credenciales de cliente inválidas");
    }
}
