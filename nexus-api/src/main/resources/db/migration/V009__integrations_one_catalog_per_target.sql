-- Una sola integración CATALOG habilitada por conexión destino (la regla de UpsertIntegration y
-- ChangeIntegrationState, ahora también garantizada ante creaciones concurrentes).
-- Si ya existen duplicados habilitados, la creación del índice falla y la migración se detiene: hay que
-- resolverlos a mano (pausar o deshabilitar uno de cada par), nunca se borra nada automáticamente.
CREATE UNIQUE INDEX uq_integrations_catalog_enabled_target
    ON integrations (tenant_id, target_connection_id)
    WHERE flow = 'CATALOG' AND state = 'ENABLED';
