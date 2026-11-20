-- Items (SKUs): the products the warehouse stores and moves.
-- Dimensions and weight describe one each (the base unit) and are optional,
-- but each group is all-or-nothing.
CREATE TABLE items (
    id                 UUID          NOT NULL,
    item_id            VARCHAR(40)   NOT NULL,
    description        VARCHAR(200)  NOT NULL,
    status             VARCHAR(10)   NOT NULL,
    barcode            VARCHAR(50),
    lot_controlled     BOOLEAN       NOT NULL,
    serial_controlled  BOOLEAN       NOT NULL,
    expiry_controlled  BOOLEAN       NOT NULL,
    shelf_life_days    INTEGER,
    width              NUMERIC(10, 2),
    depth              NUMERIC(10, 2),
    height             NUMERIC(10, 2),
    dimension_uom      VARCHAR(4),
    weight             NUMERIC(12, 3),
    weight_uom         VARCHAR(4),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT        NOT NULL,
    CONSTRAINT pk_items PRIMARY KEY (id),
    CONSTRAINT uq_items_item_id UNIQUE (item_id),
    CONSTRAINT uq_items_barcode UNIQUE (barcode),
    CONSTRAINT ck_items_dimensions CHECK (
        (width IS NULL AND depth IS NULL AND height IS NULL AND dimension_uom IS NULL)
        OR (width > 0 AND depth > 0 AND height > 0 AND dimension_uom IS NOT NULL)),
    CONSTRAINT ck_items_weight CHECK (
        (weight IS NULL AND weight_uom IS NULL) OR (weight > 0 AND weight_uom IS NOT NULL)),
    CONSTRAINT ck_items_shelf_life CHECK (
        shelf_life_days IS NULL OR (expiry_controlled AND shelf_life_days > 0))
);

CREATE INDEX ix_items_status ON items (status);
