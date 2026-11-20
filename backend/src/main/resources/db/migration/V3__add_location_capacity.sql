-- Capacity limits for locations. All optional; a missing value means no limit.
ALTER TABLE locations ADD COLUMN max_weight     NUMERIC(12, 3);
ALTER TABLE locations ADD COLUMN max_weight_uom VARCHAR(4);
ALTER TABLE locations ADD COLUMN max_volume     NUMERIC(14, 3);
ALTER TABLE locations ADD COLUMN max_volume_uom VARCHAR(6);
ALTER TABLE locations ADD COLUMN max_lpns       INTEGER;
ALTER TABLE locations ADD COLUMN max_skus       INTEGER;
ALTER TABLE locations ADD COLUMN max_units      INTEGER;

ALTER TABLE locations ADD CONSTRAINT ck_locations_max_weight CHECK (
    (max_weight IS NULL AND max_weight_uom IS NULL) OR (max_weight > 0 AND max_weight_uom IS NOT NULL));
ALTER TABLE locations ADD CONSTRAINT ck_locations_max_volume CHECK (
    (max_volume IS NULL AND max_volume_uom IS NULL) OR (max_volume > 0 AND max_volume_uom IS NOT NULL));
ALTER TABLE locations ADD CONSTRAINT ck_locations_max_counts CHECK (
    (max_lpns IS NULL OR max_lpns > 0) AND (max_skus IS NULL OR max_skus > 0) AND (max_units IS NULL OR max_units > 0));
