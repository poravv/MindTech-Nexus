-- Inicio de la última corrida de catálogo que vio el producto en el POS: lo mapeado que una corrida completa
-- no vio se archiva en GiftHub.
ALTER TABLE entity_mappings ADD COLUMN last_seen_at timestamptz;
