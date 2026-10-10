package com.mindtechpy.nexus.infrastructure.config;

import com.mindtechpy.nexus.application.port.out.*;
import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.SagaRunner;
import com.mindtechpy.nexus.application.usecase.*;
import com.mindtechpy.nexus.domain.connection.BaseUrlPolicy;
import com.mindtechpy.nexus.infrastructure.crypto.AesGcmSecretCipher;
import com.mindtechpy.nexus.infrastructure.security.HmacJwtSupport;
import org.springframework.beans.factory.ObjectProvider;
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
                                  BaseUrlPolicy baseUrlPolicy, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new TestConnection(connections, cipher, health, baseUrlPolicy, audit, clock, tx);
    }

    @Bean
    ListAuditEvents listAuditEvents(AuditRepository audit) {
        return new ListAuditEvents(audit);
    }

    /** Every {@link Saga} bean is registered by its kind; S3 and S5 plug in by declaring theirs. */
    @Bean
    SagaRunner sagaRunner(ObjectProvider<Saga> sagas, OperationRepository operations, IntegrationRepository integrations,
                          ConnectionRepository connections, SecretCipherPort cipher, RateLimiterPort rateLimiter,
                          OperationMetrics metrics, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new SagaRunner(sagas.orderedStream().toList(), operations, integrations, connections, cipher, rateLimiter,
                metrics, audit, clock, tx);
    }

    @Bean
    StartOperation startOperation(OperationRepository operations, IntegrationRepository integrations,
                                  ConnectionRepository connections, SecretCipherPort cipher, OperationMetrics metrics,
                                  ClockPort clock, TransactionRunner tx, NexusProperties properties) {
        return new StartOperation(operations, integrations, connections, cipher, metrics, clock, tx,
                properties.queue().maxUnfinishedPerTenant());
    }

    @Bean
    OperationQueries operationQueries(OperationRepository operations, ClockPort clock) {
        return new OperationQueries(operations, clock);
    }

    @Bean
    ReplayOperation replayOperation(OperationRepository operations, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new ReplayOperation(operations, audit, clock, tx);
    }

    @Bean
    UpsertIntegration upsertIntegration(IntegrationRepository integrations, ConnectionRepository connections,
                                        TenantRepository tenants, AuditRepository audit, ClockPort clock, TransactionRunner tx) {
        return new UpsertIntegration(integrations, connections, tenants, audit, clock, tx);
    }

    @Bean
    IntegrationQueries integrationQueries(IntegrationRepository integrations) {
        return new IntegrationQueries(integrations);
    }

    @Bean
    ChangeIntegrationState changeIntegrationState(IntegrationRepository integrations, AuditRepository audit, ClockPort clock,
                                                  TransactionRunner tx) {
        return new ChangeIntegrationState(integrations, audit, clock, tx);
    }
}
