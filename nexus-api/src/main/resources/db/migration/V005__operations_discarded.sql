-- DISCARDED: un humano cierra una operación DEAD sin reprocesarla; sale de la DLQ y ya no degrada la integración.
ALTER TABLE operations DROP CONSTRAINT operations_status_check;
ALTER TABLE operations ADD CONSTRAINT operations_status_check
    CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'FAILED', 'DEAD', 'DISCARDED'));
