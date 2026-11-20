-- Locations: places where inventory is stored and/or acted on.
-- The identifier columns are nullable because each location type requires a
-- different set; storage locations require all six (enforced in the service).
CREATE TABLE locations (
    id             UUID          NOT NULL,
    location_id    VARCHAR(40)   NOT NULL,
    location_type  VARCHAR(20)   NOT NULL,
    area_code      VARCHAR(20),
    zone_code      VARCHAR(20),
    aisle_code     VARCHAR(20),
    bay_code       VARCHAR(20),
    level_code     VARCHAR(20),
    position_code  VARCHAR(20),
    width          NUMERIC(10, 2) NOT NULL,
    depth          NUMERIC(10, 2) NOT NULL,
    height         NUMERIC(10, 2) NOT NULL,
    dimension_uom  VARCHAR(4)    NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT        NOT NULL,
    CONSTRAINT pk_locations PRIMARY KEY (id),
    CONSTRAINT uq_locations_location_id UNIQUE (location_id),
    CONSTRAINT uq_locations_identifiers UNIQUE (location_type, area_code, zone_code, aisle_code, bay_code, level_code, position_code),
    CONSTRAINT ck_locations_dimensions CHECK (width > 0 AND depth > 0 AND height > 0)
);

CREATE INDEX ix_locations_area_zone ON locations (area_code, zone_code);
