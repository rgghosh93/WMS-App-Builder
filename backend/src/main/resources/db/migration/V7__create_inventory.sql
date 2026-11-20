-- Containers can now sit inside other containers (a pallet LPN holding carton LPNs),
-- and LPNs are either permanent (totes, bins: the Containers page) or created with
-- inventory (a received pallet of single-use cartons).
ALTER TABLE containers ADD COLUMN permanent BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE containers ADD COLUMN parent_container_id UUID;
ALTER TABLE containers ADD CONSTRAINT fk_containers_parent
    FOREIGN KEY (parent_container_id) REFERENCES containers (id);
-- A container is either at a location or inside another container, never both.
ALTER TABLE containers ADD CONSTRAINT ck_containers_location_or_parent
    CHECK (current_location_id IS NULL OR parent_container_id IS NULL);
ALTER TABLE containers ADD CONSTRAINT ck_containers_not_own_parent
    CHECK (parent_container_id IS NULL OR parent_container_id <> id);
CREATE INDEX ix_containers_parent ON containers (parent_container_id);

-- On-hand inventory: a quantity of one item, in eaches, with its attributes,
-- either loose in a location or inside a container.
CREATE TABLE inventory (
    id                UUID          NOT NULL,
    item_id           UUID          NOT NULL,
    quantity          INTEGER       NOT NULL,
    location_id       UUID,
    container_id      UUID,
    lot_number        VARCHAR(40),
    serial_number     VARCHAR(60),
    expiry_date       DATE,
    status            VARCHAR(12)   NOT NULL,
    received_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT        NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (id),
    CONSTRAINT fk_inventory_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_inventory_location FOREIGN KEY (location_id) REFERENCES locations (id),
    CONSTRAINT fk_inventory_container FOREIGN KEY (container_id) REFERENCES containers (id),
    CONSTRAINT ck_inventory_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inventory_place CHECK (
        (location_id IS NOT NULL AND container_id IS NULL) OR (location_id IS NULL AND container_id IS NOT NULL)),
    CONSTRAINT ck_inventory_serial_quantity CHECK (serial_number IS NULL OR quantity = 1)
);

CREATE INDEX ix_inventory_item ON inventory (item_id);
CREATE INDEX ix_inventory_location ON inventory (location_id);
CREATE INDEX ix_inventory_container ON inventory (container_id);
CREATE UNIQUE INDEX uq_inventory_item_serial ON inventory (item_id, serial_number);
