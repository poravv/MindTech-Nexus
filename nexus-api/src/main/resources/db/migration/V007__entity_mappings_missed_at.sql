-- Inicio de la primera corrida completa de catálogo que no vio el producto (NULL = visto en la última).
-- Se archiva recién si la corrida siguiente tampoco lo ve: la paginación por offset del POS puede saltear un
-- producto en una corrida si el catálogo cambia mientras se pagina.
ALTER TABLE entity_mappings ADD COLUMN missed_at timestamptz;
