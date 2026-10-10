package com.mindtechpy.nexus.infrastructure.security;

import com.mindtechpy.nexus.infrastructure.web.ProblemResponses;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stateless security. Machine tokens ({@code typ=client}) only reach {@code /api/v1/**}; console
 * tokens ({@code typ=user}) only reach {@code /api/admin/**}. Role and tenant checks happen in the
 * use cases; actuator lives on the management port and is not exposed publicly (spec §4).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String CLIENT_AUTHORITY = "TYPE_" + HmacJwtSupport.TYPE_CLIENT;
    static final String USER_AUTHORITY = "TYPE_" + HmacJwtSupport.TYPE_USER;

    @Bean
    @Order(1)
    SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, HmacJwtSupport jwt, ProblemResponses problems) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) ->
                problems.write(response, HttpStatus.UNAUTHORIZED, "unauthenticated", "Token ausente, inválido o expirado");
        AccessDeniedHandler forbidden = (request, response, e) ->
                problems.write(response, HttpStatus.FORBIDDEN, "forbidden", "El token no permite acceder a este recurso");
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/oauth/token").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/admin/auth/login", "/api/admin/auth/refresh",
                                "/api/admin/auth/logout").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .requestMatchers("/api/v1/**").hasAuthority(CLIENT_AUTHORITY)
                        .requestMatchers("/api/admin/**").hasAuthority(USER_AUTHORITY)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwtConfig -> jwtConfig.decoder(jwt.decoder()).jwtAuthenticationConverter(SecurityConfig::toAuthentication))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .addFilterBefore(new LoginRateLimitFilter(problems), BearerTokenAuthenticationFilter.class)
                .build();
    }

    /** {@code TYPE_client} + {@code SCOPE_*} for machines; {@code TYPE_user} + {@code ROLE_*} for console users. */
    private static AbstractAuthenticationToken toAuthentication(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        String type = jwt.getClaimAsString("typ");
        if (HmacJwtSupport.TYPE_CLIENT.equals(type)) {
            authorities.add(new SimpleGrantedAuthority(CLIENT_AUTHORITY));
            String scope = jwt.getClaimAsString("scope");
            if (scope != null && !scope.isBlank()) {
                Arrays.stream(scope.split(" ")).forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
            }
        } else if (HmacJwtSupport.TYPE_USER.equals(type)) {
            authorities.add(new SimpleGrantedAuthority(USER_AUTHORITY));
            authorities.add(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role")));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
