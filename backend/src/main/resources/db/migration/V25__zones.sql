-- Groupings of storage locations for one function each. Not the zone in a location's address.
CREATE TABLE zones (
    id             UUID          NOT NULL,
    zone_function  VARCHAR(16)   NOT NULL,
    code           VARCHAR(40)   NOT NULL,
    name           VARCHAR(120)  NOT NULL,
    status         VARCHAR(10)   NOT NULL,
    priority       INTEGER,
    allocatable    BOOLEAN       DEFAULT TRUE NOT NULL,
    version        BIGINT        NOT NULL,
    CONSTRAINT pk_zones PRIMARY KEY (id),
    CONSTRAINT uq_zones_code UNIQUE (zone_function, code)
);

-- A location is in at most one zone of each function.
CREATE TABLE zone_locations (
    id             UUID         NOT NULL,
    zone_id        UUID         NOT NULL,
    zone_function  VARCHAR(16)  NOT NULL,
    location_id    UUID         NOT NULL,
    CONSTRAINT pk_zone_locations PRIMARY KEY (id),
    CONSTRAINT uq_zone_locations UNIQUE (zone_function, location_id),
    CONSTRAINT fk_zone_locations_zone FOREIGN KEY (zone_id) REFERENCES zones (id) ON DELETE CASCADE,
    CONSTRAINT fk_zone_locations_location FOREIGN KEY (location_id) REFERENCES locations (id) ON DELETE CASCADE
);
CREATE INDEX ix_zone_locations_zone ON zone_locations (zone_id);
CREATE INDEX ix_zone_locations_location ON zone_locations (location_id);

ALTER TABLE putaway_rules ADD COLUMN zone_id UUID;
ALTER TABLE putaway_rules ADD CONSTRAINT fk_putaway_rules_zone FOREIGN KEY (zone_id) REFERENCES zones (id);

ALTER TABLE pick_tasks ADD COLUMN pick_zone_id UUID;
ALTER TABLE pick_tasks ADD CONSTRAINT fk_pick_tasks_zone FOREIGN KEY (pick_zone_id) REFERENCES zones (id) ON DELETE SET NULL;
CREATE INDEX ix_pick_tasks_zone ON pick_tasks (pick_zone_id, status);
