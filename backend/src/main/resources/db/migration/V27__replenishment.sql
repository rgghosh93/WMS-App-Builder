CREATE TABLE replenishment_control (
    id  INTEGER NOT NULL,
    CONSTRAINT pk_replenishment_control PRIMARY KEY (id)
);
INSERT INTO replenishment_control (id) VALUES (1);

-- Keeps an item stocked in a pick location: below the minimum, it is filled to the maximum.
CREATE TABLE replenishment_rules (
    id              UUID         NOT NULL,
    item_id         UUID         NOT NULL,
    location_id     UUID         NOT NULL,
    min_quantity    INTEGER      NOT NULL,
    max_quantity    INTEGER      NOT NULL,
    multiple        INTEGER      NOT NULL,
    source_zone_id  UUID,
    for_orders      BOOLEAN      NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    version         BIGINT       NOT NULL,
    CONSTRAINT pk_replenishment_rules PRIMARY KEY (id),
    CONSTRAINT uq_replenishment_rules UNIQUE (item_id, location_id),
    CONSTRAINT fk_replenishment_rules_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_replenishment_rules_location FOREIGN KEY (location_id) REFERENCES locations (id),
    CONSTRAINT fk_replenishment_rules_zone FOREIGN KEY (source_zone_id) REFERENCES zones (id),
    CONSTRAINT ck_replenishment_rules_levels CHECK (min_quantity >= 0 AND max_quantity > min_quantity AND multiple > 0)
);
CREATE INDEX ix_replenishment_rules_location ON replenishment_rules (location_id);

CREATE SEQUENCE replenishment_task_number_seq START WITH 1;

-- One per inventory record to take from. What is not moved yet of an open or started one is set aside.
CREATE TABLE replenishment_tasks (
    id                UUID          NOT NULL,
    task_number       VARCHAR(20)   NOT NULL,
    status            VARCHAR(12)   NOT NULL,
    reason            VARCHAR(20)   NOT NULL,
    priority          INTEGER       NOT NULL,
    rule_id           UUID,
    item_id           UUID          NOT NULL,
    inventory_id      UUID          NOT NULL,
    lot_number        VARCHAR(40),
    expiry_date       DATE,
    from_location_id  UUID          NOT NULL,
    from_lpn          VARCHAR(40),
    to_location_id    UUID          NOT NULL,
    quantity          INTEGER       NOT NULL,
    moved_quantity    INTEGER       NOT NULL,
    short_quantity    INTEGER       NOT NULL,
    assigned_to       UUID,
    note              VARCHAR(400),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at        TIMESTAMP WITH TIME ZONE,
    ended_at          TIMESTAMP WITH TIME ZONE,
    version           BIGINT        NOT NULL,
    CONSTRAINT pk_replenishment_tasks PRIMARY KEY (id),
    CONSTRAINT uq_replenishment_tasks_number UNIQUE (task_number),
    CONSTRAINT fk_replenishment_tasks_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_replenishment_tasks_from FOREIGN KEY (from_location_id) REFERENCES locations (id),
    CONSTRAINT fk_replenishment_tasks_to FOREIGN KEY (to_location_id) REFERENCES locations (id),
    CONSTRAINT fk_replenishment_tasks_user FOREIGN KEY (assigned_to) REFERENCES app_users (id)
);
CREATE INDEX ix_replenishment_tasks_status ON replenishment_tasks (status, priority, created_at);
CREATE INDEX ix_replenishment_tasks_inventory ON replenishment_tasks (inventory_id, status);
CREATE INDEX ix_replenishment_tasks_to ON replenishment_tasks (to_location_id, status);
CREATE INDEX ix_replenishment_tasks_rule ON replenishment_tasks (rule_id, status);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r CROSS JOIN (VALUES ('REPLENISHMENT'), ('DEVICE_REPLENISH')) AS p (permission)
WHERE r.id = CAST('00000000-0000-4000-8000-000000000002' AS UUID);
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, 'DEVICE_REPLENISH' FROM roles r WHERE r.id = CAST('00000000-0000-4000-8000-000000000003' AS UUID);
