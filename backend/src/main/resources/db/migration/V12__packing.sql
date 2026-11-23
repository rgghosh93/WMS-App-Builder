-- Packing: one task per outbound container dropped at a pack station.
CREATE SEQUENCE pack_task_number_seq START WITH 1;
CREATE SEQUENCE carton_number_seq START WITH 1;

CREATE TABLE pack_tasks (
    id            UUID         NOT NULL,
    task_number   VARCHAR(20)  NOT NULL,
    pick_task_id  UUID         NOT NULL,
    container_id  UUID         NOT NULL,
    station_id    UUID         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    assigned_to   UUID,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at    TIMESTAMP WITH TIME ZONE,
    completed_at  TIMESTAMP WITH TIME ZONE,
    version       BIGINT       NOT NULL,
    CONSTRAINT pk_pack_tasks PRIMARY KEY (id),
    CONSTRAINT uq_pack_tasks_number UNIQUE (task_number),
    CONSTRAINT uq_pack_tasks_pick_task UNIQUE (pick_task_id),
    CONSTRAINT fk_pack_tasks_pick_task FOREIGN KEY (pick_task_id) REFERENCES pick_tasks (id),
    CONSTRAINT fk_pack_tasks_container FOREIGN KEY (container_id) REFERENCES containers (id),
    CONSTRAINT fk_pack_tasks_station FOREIGN KEY (station_id) REFERENCES locations (id),
    CONSTRAINT fk_pack_tasks_assigned_to FOREIGN KEY (assigned_to) REFERENCES app_users (id)
);
CREATE INDEX ix_pack_tasks_status ON pack_tasks (status);
CREATE INDEX ix_pack_tasks_station ON pack_tasks (station_id);
CREATE INDEX ix_pack_tasks_container ON pack_tasks (container_id);

-- What the container holds: one detail per picked task detail. allocation_id is the
-- reservation of what has not been packed yet.
CREATE TABLE pack_task_details (
    id                   UUID     NOT NULL,
    pack_task_id         UUID     NOT NULL,
    detail_number        INTEGER  NOT NULL,
    order_id             UUID     NOT NULL,
    order_line_id        UUID     NOT NULL,
    item_id              UUID     NOT NULL,
    pick_task_detail_id  UUID     NOT NULL,
    allocation_id        UUID,
    lot_number           VARCHAR(40),
    serial_number        VARCHAR(60),
    quantity             INTEGER  NOT NULL,
    packed_quantity      INTEGER  DEFAULT 0 NOT NULL,
    short_quantity       INTEGER  DEFAULT 0 NOT NULL,
    version              BIGINT   NOT NULL,
    CONSTRAINT pk_pack_task_details PRIMARY KEY (id),
    CONSTRAINT uq_pack_task_details_number UNIQUE (pack_task_id, detail_number),
    CONSTRAINT fk_pack_task_details_task FOREIGN KEY (pack_task_id) REFERENCES pack_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_pack_task_details_order FOREIGN KEY (order_id) REFERENCES outbound_orders (id),
    CONSTRAINT fk_pack_task_details_line FOREIGN KEY (order_line_id) REFERENCES order_lines (id),
    CONSTRAINT fk_pack_task_details_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_pack_task_details_pick FOREIGN KEY (pick_task_detail_id) REFERENCES pick_task_details (id),
    CONSTRAINT fk_pack_task_details_allocation FOREIGN KEY (allocation_id) REFERENCES allocations (id) ON DELETE SET NULL,
    CONSTRAINT ck_pack_task_details_quantities CHECK (
        quantity > 0 AND packed_quantity >= 0 AND short_quantity >= 0 AND packed_quantity + short_quantity <= quantity)
);
CREATE INDEX ix_pack_task_details_order ON pack_task_details (order_id);
CREATE INDEX ix_pack_task_details_allocation ON pack_task_details (allocation_id);

-- Shipping cartons, each for one order.
CREATE TABLE pack_cartons (
    id            UUID         NOT NULL,
    pack_task_id  UUID         NOT NULL,
    order_id      UUID         NOT NULL,
    container_id  UUID         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    weight        NUMERIC(12, 3),
    weight_uom    VARCHAR(4),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at     TIMESTAMP WITH TIME ZONE,
    closed_by     UUID,
    CONSTRAINT pk_pack_cartons PRIMARY KEY (id),
    CONSTRAINT fk_pack_cartons_task FOREIGN KEY (pack_task_id) REFERENCES pack_tasks (id) ON DELETE CASCADE,
    CONSTRAINT fk_pack_cartons_order FOREIGN KEY (order_id) REFERENCES outbound_orders (id),
    CONSTRAINT fk_pack_cartons_container FOREIGN KEY (container_id) REFERENCES containers (id),
    CONSTRAINT fk_pack_cartons_closed_by FOREIGN KEY (closed_by) REFERENCES app_users (id),
    CONSTRAINT ck_pack_cartons_weight CHECK (weight IS NULL OR weight > 0)
);
CREATE INDEX ix_pack_cartons_task ON pack_cartons (pack_task_id);
CREATE INDEX ix_pack_cartons_order ON pack_cartons (order_id);

-- What is in each carton.
CREATE TABLE pack_carton_lines (
    id                   UUID     NOT NULL,
    carton_id            UUID     NOT NULL,
    pack_task_detail_id  UUID     NOT NULL,
    allocation_id        UUID,
    quantity             INTEGER  NOT NULL,
    CONSTRAINT pk_pack_carton_lines PRIMARY KEY (id),
    CONSTRAINT uq_pack_carton_lines UNIQUE (carton_id, pack_task_detail_id),
    CONSTRAINT fk_pack_carton_lines_carton FOREIGN KEY (carton_id) REFERENCES pack_cartons (id) ON DELETE CASCADE,
    CONSTRAINT fk_pack_carton_lines_detail FOREIGN KEY (pack_task_detail_id) REFERENCES pack_task_details (id),
    CONSTRAINT fk_pack_carton_lines_allocation FOREIGN KEY (allocation_id) REFERENCES allocations (id) ON DELETE SET NULL,
    CONSTRAINT ck_pack_carton_lines_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_pack_carton_lines_detail ON pack_carton_lines (pack_task_detail_id);
CREATE INDEX ix_pack_carton_lines_allocation ON pack_carton_lines (allocation_id);

ALTER TABLE order_lines ADD COLUMN packed_quantity INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE order_lines ADD CONSTRAINT ck_order_lines_packed
    CHECK (packed_quantity >= 0 AND packed_quantity <= picked_quantity);
