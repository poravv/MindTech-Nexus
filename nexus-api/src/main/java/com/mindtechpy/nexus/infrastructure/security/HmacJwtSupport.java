package com.mindtechpy.nexus.infrastructure.security;

import com.mindtechpy.nexus.application.port.out.IdentityProviderPort.AuthenticatedUser;
import com.mindtechpy.nexus.application.usecase.IssueClientToken.ClientGrant;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * Issues and verifies Nexus' own HS256 tokens (ADR-003). Only Nexus verifies them, so a shared
 * secret is enough; {@code typ} separates machine ({@code client}) from console ({@code user}) tokens.
 */
public class HmacJwtSupport {

    public static final String ISSUER = "nexus";
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    public static final String TYPE_CLIENT = "client";
    public static final String TYPE_USER = "user";

    public record IssuedToken(String value, long expiresInSeconds) {
        @Override
        public String toString() {
            return "IssuedToken[***]";
        }
    }

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    /** @param base64Secret at least 32 bytes encoded in base64 ({@code NEXUS_JWT_SIGNING_KEY}). */
    public HmacJwtSupport(String base64Secret) {
        SecretKey key = new SecretKeySpec(decode(base64Secret), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = nimbus;
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public IssuedToken issueForClient(ClientGrant grant, Instant now) {
        String scope = grant.scopes().stream().map(Scope::value).sorted().collect(Collectors.joining(" "));
        return encode(base(grant.clientId(), TYPE_CLIENT, now)
                .claim("tenant_id", grant.tenantId().toString())
                .claim("scope", scope)
                .build());
    }

    public IssuedToken issueForUser(AuthenticatedUser user, Instant now) {
        JwtClaimsSet.Builder claims = base(user.userId().toString(), TYPE_USER, now)
                .claim("role", user.role().value())
                .claim("must_change_password", user.mustChangePassword());
        if (user.tenantId() != null) {
            claims.claim("tenant_id", user.tenantId().toString());
        }
        return encode(claims.build());
    }

    private static JwtClaimsSet.Builder base(String subject, String type, Instant now) {
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_TTL))
                .claim("typ", type);
    }

    private IssuedToken encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, ACCESS_TOKEN_TTL.toSeconds());
    }

    private static byte[] decode(String base64Secret) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Secret == null ? "" : base64Secret.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("NEXUS_JWT_SIGNING_KEY no es base64 válido");
        }
        if (raw.length < 32) {
            throw new IllegalArgumentException("NEXUS_JWT_SIGNING_KEY debe tener al menos 256 bits");
        }
        return raw;
    }
}
