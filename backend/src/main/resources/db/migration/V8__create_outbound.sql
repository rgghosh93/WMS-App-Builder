-- Outbound: customer orders, the stock allocated to their lines, and the pick tasks
-- created from those allocations. Quantities are in eaches.

CREATE SEQUENCE order_number_seq START WITH 1001;
CREATE SEQUENCE pick_task_number_seq START WITH 1;

CREATE TABLE outbound_orders (
    id                   UUID          NOT NULL,
    order_number         VARCHAR(40)   NOT NULL,
    customer_name        VARCHAR(120)  NOT NULL,
    requested_ship_date  DATE,
    priority             INTEGER       NOT NULL,
    status               VARCHAR(24)   NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT        NOT NULL,
    CONSTRAINT pk_outbound_orders PRIMARY KEY (id),
    CONSTRAINT uq_outbound_orders_number UNIQUE (order_number),
    CONSTRAINT ck_outbound_orders_priority CHECK (priority BETWEEN 1 AND 9)
);
CREATE INDEX ix_outbound_orders_status ON outbound_orders (status);

CREATE TABLE order_lines (
    id                  UUID          NOT NULL,
    order_id            UUID          NOT NULL,
    line_number         INTEGER       NOT NULL,
    item_id             UUID          NOT NULL,
    ordered_quantity    INTEGER       NOT NULL,
    allocated_quantity  INTEGER       NOT NULL,
    requested_lot       VARCHAR(40),
    status              VARCHAR(24)   NOT NULL,
    CONSTRAINT pk_order_lines PRIMARY KEY (id),
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES outbound_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_lines_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT uq_order_lines_number UNIQUE (order_id, line_number),
    CONSTRAINT ck_order_lines_quantities CHECK (
        ordered_quantity > 0 AND allocated_quantity >= 0 AND allocated_quantity <= ordered_quantity)
);
CREATE INDEX ix_order_lines_item ON order_lines (item_id);

-- Stock reserved for an order line. Free stock = inventory quantity minus its allocations.
CREATE TABLE allocations (
    id             UUID     NOT NULL,
    order_line_id  UUID     NOT NULL,
    inventory_id   UUID     NOT NULL,
    quantity       INTEGER  NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_allocations PRIMARY KEY (id),
    CONSTRAINT fk_allocations_line FOREIGN KEY (order_line_id) REFERENCES order_lines (id) ON DELETE CASCADE,
    CONSTRAINT fk_allocations_inventory FOREIGN KEY (inventory_id) REFERENCES inventory (id),
    CONSTRAINT ck_allocations_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_allocations_line ON allocations (order_line_id);
CREATE INDEX ix_allocations_inventory ON allocations (inventory_id);

-- Work for a picker: take a quantity of an item from a location (and container, if any).
CREATE TABLE pick_tasks (
    id                 UUID          NOT NULL,
    task_number        VARCHAR(20)   NOT NULL,
    order_id           UUID          NOT NULL,
    order_line_id      UUID          NOT NULL,
    allocation_id      UUID,
    item_id            UUID          NOT NULL,
    quantity           INTEGER       NOT NULL,
    from_location_id   UUID          NOT NULL,
    from_container_id  UUID,
    lot_number         VARCHAR(40),
    serial_number      VARCHAR(60),
    status             VARCHAR(16)   NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version            BIGINT        NOT NULL,
    CONSTRAINT pk_pick_tasks PRIMARY KEY (id),
    CONSTRAINT uq_pick_tasks_number UNIQUE (task_number),
    CONSTRAINT fk_pick_tasks_order FOREIGN KEY (order_id) REFERENCES outbound_orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_pick_tasks_line FOREIGN KEY (order_line_id) REFERENCES order_lines (id) ON DELETE CASCADE,
    CONSTRAINT fk_pick_tasks_allocation FOREIGN KEY (allocation_id) REFERENCES allocations (id) ON DELETE SET NULL,
    CONSTRAINT fk_pick_tasks_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_pick_tasks_location FOREIGN KEY (from_location_id) REFERENCES locations (id),
    CONSTRAINT fk_pick_tasks_container FOREIGN KEY (from_container_id) REFERENCES containers (id),
    CONSTRAINT ck_pick_tasks_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_pick_tasks_order ON pick_tasks (order_id);
CREATE INDEX ix_pick_tasks_status ON pick_tasks (status);

-- What happened to an order, in order: received, allocated, tasks created, cancelled...
CREATE TABLE order_events (
    id           UUID          NOT NULL,
    order_id     UUID          NOT NULL,
    occurred_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    event_type   VARCHAR(24)   NOT NULL,
    message      VARCHAR(500)  NOT NULL,
    CONSTRAINT pk_order_events PRIMARY KEY (id),
    CONSTRAINT fk_order_events_order FOREIGN KEY (order_id) REFERENCES outbound_orders (id) ON DELETE CASCADE
);
CREATE INDEX ix_order_events_order ON order_events (order_id, occurred_at);
