-- Units of measure (EA, IP, CS, PL...) and each item's packaging levels.
-- EA (each) is the base unit every quantity is counted in; it is created here
-- because the rest of the system depends on it.
CREATE TABLE units_of_measure (
    id          UUID          NOT NULL,
    uom_code    VARCHAR(10)   NOT NULL,
    description VARCHAR(100)  NOT NULL,
    kind        VARCHAR(12)   NOT NULL,
    status      VARCHAR(10)   NOT NULL,
    base        BOOLEAN       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT        NOT NULL,
    CONSTRAINT pk_units_of_measure PRIMARY KEY (id),
    CONSTRAINT uq_units_of_measure_code UNIQUE (uom_code)
);

INSERT INTO units_of_measure (id, uom_code, description, kind, status, base, created_at, updated_at, version)
VALUES ('00000000-0000-0000-0000-0000000000ea', 'EA', 'Each', 'EACH', 'ACTIVE', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- A packaging level of one item: how many eaches it holds, plus its own barcode, size and weight.
CREATE TABLE item_uoms (
    id             UUID           NOT NULL,
    item_id        UUID           NOT NULL,
    uom_id         UUID           NOT NULL,
    quantity       INTEGER        NOT NULL,
    barcode        VARCHAR(50),
    width          NUMERIC(10, 2),
    depth          NUMERIC(10, 2),
    height         NUMERIC(10, 2),
    dimension_uom  VARCHAR(4),
    weight         NUMERIC(12, 3),
    weight_uom     VARCHAR(4),
    CONSTRAINT pk_item_uoms PRIMARY KEY (id),
    CONSTRAINT fk_item_uoms_item FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_uoms_uom FOREIGN KEY (uom_id) REFERENCES units_of_measure (id),
    CONSTRAINT uq_item_uoms_item_uom UNIQUE (item_id, uom_id),
    CONSTRAINT uq_item_uoms_barcode UNIQUE (barcode),
    CONSTRAINT ck_item_uoms_quantity CHECK (quantity >= 2),
    CONSTRAINT ck_item_uoms_dimensions CHECK (
        (width IS NULL AND depth IS NULL AND height IS NULL AND dimension_uom IS NULL)
        OR (width > 0 AND depth > 0 AND height > 0 AND dimension_uom IS NOT NULL)),
    CONSTRAINT ck_item_uoms_weight CHECK (
        (weight IS NULL AND weight_uom IS NULL) OR (weight > 0 AND weight_uom IS NOT NULL))
);

CREATE INDEX ix_item_uoms_uom ON item_uoms (uom_id);
