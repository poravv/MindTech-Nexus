-- Salud por integración y serie temporal (consola).
-- La DLQ por integración se cuenta sin importar la antigüedad: sin este índice recorre todas las operaciones del tenant.
CREATE INDEX idx_operations_dead ON operations (tenant_id, integration_id) WHERE status = 'DEAD';
-- platform_admin sin tenantId mira todos los tenants: ventana por created_at sin tenant_id adelante.
CREATE INDEX idx_operations_created ON operations (created_at);
