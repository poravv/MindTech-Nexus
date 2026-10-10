-- Backoff del catálogo: el scheduler lee cada minuto las últimas corridas terminadas de cada integración CATALOG.
CREATE INDEX idx_operations_integration_finished ON operations (integration_id, finished_at DESC) WHERE finished_at IS NOT NULL;
