-- Container types: kinds of containers that hold inventory (pallets, cartons,
-- totes, bins...). Outer dimensions are required; weights and inner volume are optional.
CREATE TABLE container_types (
    id                  UUID           NOT NULL,
    container_type_id   VARCHAR(40)    NOT NULL,
    description         VARCHAR(200)   NOT NULL,
    category            VARCHAR(20)    NOT NULL,
    status              VARCHAR(10)    NOT NULL,
    reusable            BOOLEAN        NOT NULL,
    width               NUMERIC(10, 2) NOT NULL,
    depth               NUMERIC(10, 2) NOT NULL,
    height              NUMERIC(10, 2) NOT NULL,
    dimension_uom       VARCHAR(4)     NOT NULL,
    tare_weight         NUMERIC(12, 3),
    tare_weight_uom     VARCHAR(4),
    max_weight          NUMERIC(12, 3),
    max_weight_uom      VARCHAR(4),
    max_volume          NUMERIC(14, 3),
    max_volume_uom      VARCHAR(6),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT         NOT NULL,
    CONSTRAINT pk_container_types PRIMARY KEY (id),
    CONSTRAINT uq_container_types_id UNIQUE (container_type_id),
    CONSTRAINT ck_container_types_dimensions CHECK (width > 0 AND depth > 0 AND height > 0),
    CONSTRAINT ck_container_types_tare CHECK (
        (tare_weight IS NULL AND tare_weight_uom IS NULL) OR (tare_weight > 0 AND tare_weight_uom IS NOT NULL)),
    CONSTRAINT ck_container_types_max_weight CHECK (
        (max_weight IS NULL AND max_weight_uom IS NULL) OR (max_weight > 0 AND max_weight_uom IS NOT NULL)),
    CONSTRAINT ck_container_types_max_volume CHECK (
        (max_volume IS NULL AND max_volume_uom IS NULL) OR (max_volume > 0 AND max_volume_uom IS NOT NULL))
);

CREATE INDEX ix_container_types_category ON container_types (category);
