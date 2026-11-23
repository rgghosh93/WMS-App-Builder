-- Carriers and the services they offer.
CREATE TABLE carriers (
    id                 UUID          NOT NULL,
    carrier_code       VARCHAR(20)   NOT NULL,
    name               VARCHAR(120)  NOT NULL,
    mode               VARCHAR(12)   NOT NULL,
    tracking_required  BOOLEAN       NOT NULL,
    status             VARCHAR(10)   NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT        NOT NULL,
    CONSTRAINT pk_carriers PRIMARY KEY (id),
    CONSTRAINT uq_carriers_code UNIQUE (carrier_code)
);

CREATE TABLE carrier_services (
    id            UUID          NOT NULL,
    carrier_id    UUID          NOT NULL,
    service_code  VARCHAR(20)   NOT NULL,
    name          VARCHAR(120)  NOT NULL,
    status        VARCHAR(10)   NOT NULL,
    CONSTRAINT pk_carrier_services PRIMARY KEY (id),
    CONSTRAINT uq_carrier_services_code UNIQUE (carrier_id, service_code),
    CONSTRAINT fk_carrier_services_carrier FOREIGN KEY (carrier_id) REFERENCES carriers (id) ON DELETE CASCADE
);

-- Where an order goes. All null until it is known.
ALTER TABLE outbound_orders ADD COLUMN ship_to_name VARCHAR(120);
ALTER TABLE outbound_orders ADD COLUMN ship_to_line1 VARCHAR(120);
ALTER TABLE outbound_orders ADD COLUMN ship_to_line2 VARCHAR(120);
ALTER TABLE outbound_orders ADD COLUMN ship_to_city VARCHAR(80);
ALTER TABLE outbound_orders ADD COLUMN ship_to_state VARCHAR(40);
ALTER TABLE outbound_orders ADD COLUMN ship_to_postal_code VARCHAR(20);
ALTER TABLE outbound_orders ADD COLUMN ship_to_country VARCHAR(2);

CREATE SEQUENCE shipment_number_seq START WITH 1;
CREATE SEQUENCE manifest_number_seq START WITH 1;

-- What leaves with one carrier from one dock door. open_door_id is the door while the
-- manifest is open and null afterwards, so a door has one open manifest at a time.
CREATE TABLE manifests (
    id               UUID         NOT NULL,
    manifest_number  VARCHAR(20)  NOT NULL,
    carrier_id       UUID         NOT NULL,
    door_id          UUID         NOT NULL,
    open_door_id     UUID,
    status           VARCHAR(10)  NOT NULL,
    trailer_number   VARCHAR(40),
    seal_number      VARCHAR(40),
    created_by       UUID         NOT NULL,
    closed_by        UUID,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at        TIMESTAMP WITH TIME ZONE,
    version          BIGINT       NOT NULL,
    CONSTRAINT pk_manifests PRIMARY KEY (id),
    CONSTRAINT uq_manifests_number UNIQUE (manifest_number),
    CONSTRAINT uq_manifests_open_door UNIQUE (open_door_id),
    CONSTRAINT fk_manifests_carrier FOREIGN KEY (carrier_id) REFERENCES carriers (id),
    CONSTRAINT fk_manifests_door FOREIGN KEY (door_id) REFERENCES locations (id),
    CONSTRAINT fk_manifests_created_by FOREIGN KEY (created_by) REFERENCES app_users (id),
    CONSTRAINT fk_manifests_closed_by FOREIGN KEY (closed_by) REFERENCES app_users (id)
);
CREATE INDEX ix_manifests_status ON manifests (status);

-- Cartons of one order going out together.
CREATE TABLE shipments (
    id                  UUID         NOT NULL,
    shipment_number     VARCHAR(20)  NOT NULL,
    order_id            UUID         NOT NULL,
    status              VARCHAR(10)  NOT NULL,
    carrier_service_id  UUID,
    manifest_id         UUID,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    shipped_at          TIMESTAMP WITH TIME ZONE,
    version             BIGINT       NOT NULL,
    CONSTRAINT pk_shipments PRIMARY KEY (id),
    CONSTRAINT uq_shipments_number UNIQUE (shipment_number),
    CONSTRAINT fk_shipments_order FOREIGN KEY (order_id) REFERENCES outbound_orders (id),
    CONSTRAINT fk_shipments_service FOREIGN KEY (carrier_service_id) REFERENCES carrier_services (id),
    CONSTRAINT fk_shipments_manifest FOREIGN KEY (manifest_id) REFERENCES manifests (id)
);
CREATE INDEX ix_shipments_order ON shipments (order_id);
CREATE INDEX ix_shipments_status ON shipments (status);
CREATE INDEX ix_shipments_manifest ON shipments (manifest_id);

ALTER TABLE pack_cartons ADD COLUMN shipment_id UUID;
ALTER TABLE pack_cartons ADD COLUMN tracking_number VARCHAR(60);
ALTER TABLE pack_cartons ADD COLUMN loaded_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE pack_cartons ADD COLUMN loaded_by UUID;
ALTER TABLE pack_cartons ADD CONSTRAINT fk_pack_cartons_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id);
ALTER TABLE pack_cartons ADD CONSTRAINT fk_pack_cartons_loaded_by FOREIGN KEY (loaded_by) REFERENCES app_users (id);
ALTER TABLE pack_cartons ADD CONSTRAINT uq_pack_cartons_tracking UNIQUE (tracking_number);
CREATE INDEX ix_pack_cartons_shipment ON pack_cartons (shipment_id);

ALTER TABLE order_lines ADD COLUMN shipped_quantity INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE order_lines ADD CONSTRAINT ck_order_lines_shipped
    CHECK (shipped_quantity >= 0 AND shipped_quantity <= packed_quantity);
