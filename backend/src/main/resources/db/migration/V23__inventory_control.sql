-- Every change to stock, for good: rows are only ever added. Codes are kept as text so the history
-- still reads right after locations, containers or people are gone.
CREATE SEQUENCE inventory_transaction_seq START WITH 1;
CREATE TABLE inventory_transactions (
    id             UUID          NOT NULL,
    number         BIGINT        NOT NULL,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    type           VARCHAR(16)   NOT NULL,
    item_id        UUID          NOT NULL,
    item_code      VARCHAR(40)   NOT NULL,
    lot_number     VARCHAR(40),
    serial_number  VARCHAR(60),
    expiry_date    DATE,
    quantity       INTEGER       NOT NULL,
    from_location  VARCHAR(40),
    from_lpn       VARCHAR(40),
    to_location    VARCHAR(40),
    to_lpn         VARCHAR(40),
    from_status    VARCHAR(12),
    to_status      VARCHAR(12),
    reference      VARCHAR(120),
    reason         VARCHAR(400),
    request_id     UUID,
    user_id        UUID,
    user_name      VARCHAR(120)  NOT NULL,
    CONSTRAINT pk_inventory_transactions PRIMARY KEY (id),
    CONSTRAINT uq_inventory_transactions_number UNIQUE (number),
    CONSTRAINT ck_inventory_transactions_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_inventory_transactions_item ON inventory_transactions (item_id, number);
CREATE INDEX ix_inventory_transactions_request ON inventory_transactions (request_id);

-- Why stock is on hold or damaged.
ALTER TABLE inventory ADD COLUMN hold_reason VARCHAR(400);
ALTER TABLE inventory ADD COLUMN held_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE reason_codes (
    id       UUID         NOT NULL,
    kind     VARCHAR(12)  NOT NULL,
    code     VARCHAR(20)  NOT NULL,
    name     VARCHAR(80)  NOT NULL,
    status   VARCHAR(10)  NOT NULL,
    version  BIGINT       NOT NULL,
    CONSTRAINT pk_reason_codes PRIMARY KEY (id),
    CONSTRAINT uq_reason_codes UNIQUE (kind, code)
);
INSERT INTO reason_codes (id, kind, code, name, status, version) VALUES
    ('00000000-0000-4000-8001-000000000001', 'ADJUSTMENT', 'DAMAGED', 'Damaged', 'ACTIVE', 0),
    ('00000000-0000-4000-8001-000000000002', 'ADJUSTMENT', 'LOST', 'Not found', 'ACTIVE', 0),
    ('00000000-0000-4000-8001-000000000003', 'ADJUSTMENT', 'FOUND', 'Found', 'ACTIVE', 0),
    ('00000000-0000-4000-8001-000000000004', 'ADJUSTMENT', 'ENTRY', 'Entry mistake', 'ACTIVE', 0),
    ('00000000-0000-4000-8001-000000000005', 'HOLD', 'QUALITY', 'Quality check', 'ACTIVE', 0),
    ('00000000-0000-4000-8001-000000000006', 'HOLD', 'DAMAGED', 'Damaged', 'ACTIVE', 0),
    ('00000000-0000-4000-8001-000000000007', 'HOLD', 'RECALL', 'Recall', 'ACTIVE', 0),
    ('00000000-0000-4000-8001-000000000008', 'HOLD', 'CHECKED', 'Checked', 'ACTIVE', 0);

-- Changes of more than this many eaches need a second person.
CREATE TABLE inventory_settings (
    id              INTEGER  NOT NULL,
    approval_limit  INTEGER  NOT NULL,
    version         BIGINT   NOT NULL,
    CONSTRAINT pk_inventory_settings PRIMARY KEY (id)
);
INSERT INTO inventory_settings (id, approval_limit, version) VALUES (1, 25, 0);

CREATE SEQUENCE adjustment_number_seq START WITH 1;
CREATE TABLE inventory_adjustments (
    id             UUID          NOT NULL,
    number         VARCHAR(20)   NOT NULL,
    status         VARCHAR(10)   NOT NULL,
    inventory_id   UUID,
    item_id        UUID          NOT NULL,
    location_id    UUID,
    container_id   UUID,
    lot_number     VARCHAR(40),
    serial_number  VARCHAR(60),
    expiry_date    DATE,
    stock_status   VARCHAR(12)   NOT NULL,
    place          VARCHAR(90)   NOT NULL,
    change         INTEGER       NOT NULL,
    reason         VARCHAR(400)  NOT NULL,
    requested_by   UUID          NOT NULL,
    requester      VARCHAR(120)  NOT NULL,
    requested_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by     UUID,
    decider        VARCHAR(120),
    decided_at     TIMESTAMP WITH TIME ZONE,
    version        BIGINT        NOT NULL,
    CONSTRAINT pk_inventory_adjustments PRIMARY KEY (id),
    CONSTRAINT uq_inventory_adjustments_number UNIQUE (number),
    CONSTRAINT fk_inventory_adjustments_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT ck_inventory_adjustments_change CHECK (change <> 0)
);
CREATE INDEX ix_inventory_adjustments_status ON inventory_adjustments (status, requested_at);

CREATE SEQUENCE count_number_seq START WITH 1;
CREATE TABLE count_tasks (
    id           UUID          NOT NULL,
    number       VARCHAR(20)   NOT NULL,
    status       VARCHAR(10)   NOT NULL,
    location_id  UUID          NOT NULL,
    planned_by   VARCHAR(120)  NOT NULL,
    planned_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    counter_id   UUID,
    counter      VARCHAR(120),
    started_at   TIMESTAMP WITH TIME ZONE,
    counted_at   TIMESTAMP WITH TIME ZONE,
    decider      VARCHAR(120),
    decided_at   TIMESTAMP WITH TIME ZONE,
    rounds       INTEGER       DEFAULT 0 NOT NULL,
    version      BIGINT        NOT NULL,
    CONSTRAINT pk_count_tasks PRIMARY KEY (id),
    CONSTRAINT uq_count_tasks_number UNIQUE (number),
    CONSTRAINT fk_count_tasks_location FOREIGN KEY (location_id) REFERENCES locations (id)
);
CREATE INDEX ix_count_tasks_location ON count_tasks (location_id, status);

-- What a counter has counted so far. request_id is made by the device, so a repeat isn't counted twice.
CREATE TABLE count_entries (
    id             UUID         NOT NULL,
    task_id        UUID         NOT NULL,
    request_id     UUID         NOT NULL,
    request_part   INTEGER      NOT NULL,
    container_id   UUID,
    item_id        UUID         NOT NULL,
    lot_number     VARCHAR(40),
    serial_number  VARCHAR(60),
    quantity       INTEGER      NOT NULL,
    counted_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_count_entries PRIMARY KEY (id),
    CONSTRAINT uq_count_entries_request UNIQUE (request_id, request_part),
    CONSTRAINT fk_count_entries_task FOREIGN KEY (task_id) REFERENCES count_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_count_entries_container FOREIGN KEY (container_id) REFERENCES containers (id),
    CONSTRAINT fk_count_entries_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT ck_count_entries_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_count_entries_task ON count_entries (task_id);

-- What was found against what was expected.
CREATE TABLE count_results (
    id             UUID         NOT NULL,
    task_id        UUID         NOT NULL,
    container_id   UUID,
    lpn_id         VARCHAR(40),
    item_id        UUID         NOT NULL,
    lot_number     VARCHAR(40),
    serial_number  VARCHAR(60),
    expected       INTEGER      NOT NULL,
    counted        INTEGER      NOT NULL,
    CONSTRAINT pk_count_results PRIMARY KEY (id),
    CONSTRAINT fk_count_results_task FOREIGN KEY (task_id) REFERENCES count_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_count_results_item FOREIGN KEY (item_id) REFERENCES items (id)
);
CREATE INDEX ix_count_results_task ON count_results (task_id);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r CROSS JOIN (VALUES ('INVENTORY'), ('INVENTORY_APPROVE'), ('DEVICE_MOVE'), ('DEVICE_COUNT')) AS p (permission)
WHERE r.id = CAST('00000000-0000-4000-8000-000000000002' AS UUID);
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r CROSS JOIN (VALUES ('DEVICE_MOVE'), ('DEVICE_COUNT')) AS p (permission)
WHERE r.id = CAST('00000000-0000-4000-8000-000000000003' AS UUID);
