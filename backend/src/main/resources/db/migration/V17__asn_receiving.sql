-- Advance shipping notices: what vendors say is coming.
CREATE SEQUENCE asn_number_seq START WITH 1;
CREATE SEQUENCE inbound_lpn_seq START WITH 1;

CREATE TABLE asns (
    id              UUID          NOT NULL,
    asn_number      VARCHAR(40)   NOT NULL,
    vendor_name     VARCHAR(120)  NOT NULL,
    reference       VARCHAR(60),
    expected_date   DATE,
    status          VARCHAR(12)   NOT NULL,
    closed_by       UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at       TIMESTAMP WITH TIME ZONE,
    version         BIGINT        NOT NULL,
    CONSTRAINT pk_asns PRIMARY KEY (id),
    CONSTRAINT uq_asns_number UNIQUE (asn_number),
    CONSTRAINT fk_asns_closed_by FOREIGN KEY (closed_by) REFERENCES app_users (id)
);
CREATE INDEX ix_asns_status ON asns (status);

-- lpn_id is the label of the LPN the vendor packed this line in, if they said.
CREATE TABLE asn_lines (
    id                 UUID         NOT NULL,
    asn_id             UUID         NOT NULL,
    line_number        INTEGER      NOT NULL,
    item_id            UUID         NOT NULL,
    expected_quantity  INTEGER      NOT NULL,
    received_quantity  INTEGER      DEFAULT 0 NOT NULL,
    lot_number         VARCHAR(40),
    expiry_date        DATE,
    lpn_id             VARCHAR(40),
    CONSTRAINT pk_asn_lines PRIMARY KEY (id),
    CONSTRAINT uq_asn_lines_number UNIQUE (asn_id, line_number),
    CONSTRAINT fk_asn_lines_asn FOREIGN KEY (asn_id) REFERENCES asns (id) ON DELETE CASCADE,
    CONSTRAINT fk_asn_lines_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT ck_asn_lines_quantities CHECK (expected_quantity > 0 AND received_quantity >= 0)
);
CREATE INDEX ix_asn_lines_item ON asn_lines (item_id);
CREATE INDEX ix_asn_lines_lpn ON asn_lines (lpn_id);

-- What was received. request_id is made by the device, so a repeated request is not received twice.
CREATE TABLE asn_receipts (
    id                UUID         NOT NULL,
    asn_id            UUID         NOT NULL,
    asn_line_id       UUID         NOT NULL,
    request_id        UUID         NOT NULL,
    request_part      INTEGER      NOT NULL,
    container_id      UUID         NOT NULL,
    location_id       UUID         NOT NULL,
    quantity          INTEGER      NOT NULL,
    lot_number        VARCHAR(40),
    serial_number     VARCHAR(60),
    expiry_date       DATE,
    inventory_status  VARCHAR(12)  NOT NULL,
    received_by       UUID         NOT NULL,
    received_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    reversed_by       UUID,
    reversed_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_asn_receipts PRIMARY KEY (id),
    CONSTRAINT uq_asn_receipts_request UNIQUE (request_id, request_part),
    CONSTRAINT fk_asn_receipts_asn FOREIGN KEY (asn_id) REFERENCES asns (id),
    CONSTRAINT fk_asn_receipts_line FOREIGN KEY (asn_line_id) REFERENCES asn_lines (id),
    CONSTRAINT fk_asn_receipts_container FOREIGN KEY (container_id) REFERENCES containers (id),
    CONSTRAINT fk_asn_receipts_location FOREIGN KEY (location_id) REFERENCES locations (id),
    CONSTRAINT fk_asn_receipts_received_by FOREIGN KEY (received_by) REFERENCES app_users (id),
    CONSTRAINT fk_asn_receipts_reversed_by FOREIGN KEY (reversed_by) REFERENCES app_users (id),
    CONSTRAINT ck_asn_receipts_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_asn_receipts_asn ON asn_receipts (asn_id, received_at);
CREATE INDEX ix_asn_receipts_container ON asn_receipts (container_id);

CREATE TABLE asn_events (
    id           UUID          NOT NULL,
    asn_id       UUID          NOT NULL,
    occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type   VARCHAR(24)   NOT NULL,
    message      VARCHAR(500)  NOT NULL,
    CONSTRAINT pk_asn_events PRIMARY KEY (id),
    CONSTRAINT fk_asn_events_asn FOREIGN KEY (asn_id) REFERENCES asns (id) ON DELETE CASCADE
);
CREATE INDEX ix_asn_events_asn ON asn_events (asn_id, occurred_at);
