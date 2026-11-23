-- One row, locked while a put-away location is being chosen or filled, so two people aren't sent to the same space.
CREATE TABLE putaway_control (
    id  INTEGER NOT NULL,
    CONSTRAINT pk_putaway_control PRIMARY KEY (id)
);
INSERT INTO putaway_control (id) VALUES (1);

-- Tried in order: the first rule that fits an LPN and has room decides where it goes.
CREATE TABLE putaway_rules (
    id                  UUID          NOT NULL,
    sequence_number     INTEGER       NOT NULL,
    name                VARCHAR(120)  NOT NULL,
    status              VARCHAR(10)   NOT NULL,
    item_id             UUID,
    container_category  VARCHAR(20),
    stock_status        VARCHAR(12),
    strategy            VARCHAR(20)   NOT NULL,
    location_id         UUID,
    area_code           VARCHAR(20),
    zone_code           VARCHAR(20),
    handling            VARCHAR(12)   NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    version             BIGINT        NOT NULL,
    CONSTRAINT pk_putaway_rules PRIMARY KEY (id),
    CONSTRAINT fk_putaway_rules_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_putaway_rules_location FOREIGN KEY (location_id) REFERENCES locations (id)
);

CREATE SEQUENCE putaway_task_number_seq START WITH 1;

-- One per received LPN. directed_location_id is also a reservation while the task is in progress.
CREATE TABLE putaway_tasks (
    id                    UUID         NOT NULL,
    task_number           VARCHAR(20)  NOT NULL,
    status                VARCHAR(12)  NOT NULL,
    container_id          UUID         NOT NULL,
    from_location_id      UUID         NOT NULL,
    directed_location_id  UUID,
    rule_name             VARCHAR(120),
    handling              VARCHAR(12),
    to_location_id        UUID,
    override_reason       VARCHAR(20),
    assigned_to           UUID,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at            TIMESTAMP WITH TIME ZONE,
    completed_at          TIMESTAMP WITH TIME ZONE,
    version               BIGINT       NOT NULL,
    CONSTRAINT pk_putaway_tasks PRIMARY KEY (id),
    CONSTRAINT uq_putaway_tasks_number UNIQUE (task_number),
    CONSTRAINT fk_putaway_tasks_container FOREIGN KEY (container_id) REFERENCES containers (id),
    CONSTRAINT fk_putaway_tasks_from FOREIGN KEY (from_location_id) REFERENCES locations (id),
    CONSTRAINT fk_putaway_tasks_directed FOREIGN KEY (directed_location_id) REFERENCES locations (id),
    CONSTRAINT fk_putaway_tasks_to FOREIGN KEY (to_location_id) REFERENCES locations (id),
    CONSTRAINT fk_putaway_tasks_assigned FOREIGN KEY (assigned_to) REFERENCES app_users (id)
);
CREATE INDEX ix_putaway_tasks_status ON putaway_tasks (status, created_at);
CREATE INDEX ix_putaway_tasks_container ON putaway_tasks (container_id);

-- Locations the task was sent to that couldn't be used.
CREATE TABLE putaway_skips (
    id           UUID         NOT NULL,
    task_id      UUID         NOT NULL,
    location_id  UUID         NOT NULL,
    reason       VARCHAR(20)  NOT NULL,
    skipped_by   UUID         NOT NULL,
    skipped_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_putaway_skips PRIMARY KEY (id),
    CONSTRAINT fk_putaway_skips_task FOREIGN KEY (task_id) REFERENCES putaway_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_putaway_skips_location FOREIGN KEY (location_id) REFERENCES locations (id),
    CONSTRAINT fk_putaway_skips_user FOREIGN KEY (skipped_by) REFERENCES app_users (id)
);
CREATE INDEX ix_putaway_skips_task ON putaway_skips (task_id);

-- What was put away, kept after the stock has moved on.
CREATE TABLE putaway_task_lines (
    id            UUID         NOT NULL,
    task_id       UUID         NOT NULL,
    item_id       UUID         NOT NULL,
    quantity      INTEGER      NOT NULL,
    lot_number    VARCHAR(40),
    expiry_date   DATE,
    stock_status  VARCHAR(12)  NOT NULL,
    CONSTRAINT pk_putaway_task_lines PRIMARY KEY (id),
    CONSTRAINT fk_putaway_task_lines_task FOREIGN KEY (task_id) REFERENCES putaway_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_putaway_task_lines_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT ck_putaway_task_lines_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_putaway_task_lines_task ON putaway_task_lines (task_id);
