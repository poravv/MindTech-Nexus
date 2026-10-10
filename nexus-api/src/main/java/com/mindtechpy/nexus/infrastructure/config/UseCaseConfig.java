package com.mindtechpy.nexus.infrastructure.config;

import com.mindtechpy.nexus.application.port.out.*;
import com.mindtechpy.nexus.application.usecase.*;
import com.mindtechpy.nexus.domain.connection.BaseUrlPolicy;
import com.mindtechpy.nexus.infrastructure.crypto.AesGcmSecretCipher;
import com.mindtechpy.nexus.infrastructure.security.HmacJwtSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the framework-free use cases with their adapters. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    @Bean
    SecretCipherPort secretCipher(NexusProperties properties, JsonMapper json) {
        return new AesGcmSecretCipher(properties.security().masterKey(), json);
    }

    @Bean
    HmacJwtSupport hmacJwtSupport(NexusProperties properties) {
        return new HmacJwtSupport(properties.security().jwtSecret());
    }

    @Bean
    BaseUrlPolicy baseUrlPolicy(NexusProperties properties) {
        return new BaseUrlPolicy(properties.connectors().allowedHosts());
    }

    @Bean
    CreateTenant createTenant(TenantRepository tenants, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new CreateTenant(tenants, audit, clock, tx);
    }

    @Bean
    ListTenants listTenants(TenantRepository tenants) {
        return new ListTenants(tenants);
    }

    @Bean
    CreateUser createUser(UserRepository users, TenantRepository tenants, PasswordHasherPort hasher,
                          AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new CreateUser(users, tenants, hasher, audit, clock, tx);
    }

    @Bean
    BootstrapAdmin bootstrapAdmin(UserRepository users, PasswordHasherPort hasher, AuditRepository audit,
                                  ClockPort clock, TransactionRunner tx) {
        return new BootstrapAdmin(users, hasher, audit, clock, tx);
    }

    @Bean
    ListUsers listUsers(UserRepository users) {
        return new ListUsers(users);
    }

    @Bean
    LoginUser loginUser(IdentityProviderPort identityProvider, SessionRepository sessions, AuditRepository audit,
                        ClockPort clock, TransactionRunner tx) {
        return new LoginUser(identityProvider, sessions, audit, clock, tx);
    }

    @Bean
    RefreshSession refreshSession(SessionRepository sessions, UserRepository users, ClockPort clock) {
        return new RefreshSession(sessions, users, clock);
    }

    @Bean
    Logout logout(SessionRepository sessions, ClockPort clock) {
        return new Logout(sessions, clock);
    }

    @Bean
    ChangePassword changePassword(UserRepository users, PasswordHasherPort hasher, AuditRepository audit,
                                  ClockPort clock, TransactionRunner tx) {
        return new ChangePassword(users, hasher, audit, clock, tx);
    }

    @Bean
    GetCurrentUser getCurrentUser(UserRepository users) {
        return new GetCurrentUser(users);
    }

    @Bean
    CreateApiClient createApiClient(ApiClientRepository clients, TenantRepository tenants, PasswordHasherPort hasher,
                                    AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new CreateApiClient(clients, tenants, hasher, audit, clock, tx);
    }

    @Bean
    RotateApiClientSecret rotateApiClientSecret(ApiClientRepository clients, PasswordHasherPort hasher,
                                                AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new RotateApiClientSecret(clients, hasher, audit, clock, tx);
    }

    @Bean
    RevokeApiClient revokeApiClient(ApiClientRepository clients, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new RevokeApiClient(clients, audit, clock, tx);
    }

    @Bean
    ListApiClients listApiClients(ApiClientRepository clients) {
        return new ListApiClients(clients);
    }

    @Bean
    IssueClientToken issueClientToken(ApiClientRepository clients, TenantRepository tenants, PasswordHasherPort hasher,
                                      ClockPort clock) {
        return new IssueClientToken(clients, tenants, hasher, clock);
    }

    @Bean
    UpsertConnection upsertConnection(ConnectionRepository connections, TenantRepository tenants, SecretCipherPort cipher,
                                      BaseUrlPolicy baseUrlPolicy, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new UpsertConnection(connections, tenants, cipher, baseUrlPolicy, audit, clock, tx);
    }

    @Bean
    ConnectionQueries connectionQueries(ConnectionRepository connections) {
        return new ConnectionQueries(connections);
    }

    @Bean
    TestConnection testConnection(ConnectionRepository connections, SecretCipherPort cipher, ConnectorHealthPort health,
                                  AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new TestConnection(connections, cipher, health, audit, clock, tx);
    }

    @Bean
    ListAuditEvents listAuditEvents(AuditRepository audit) {
        return new ListAuditEvents(audit);
    }
}
