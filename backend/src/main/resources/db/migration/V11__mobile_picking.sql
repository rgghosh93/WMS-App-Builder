-- People who sign in to mobile devices. PINs are stored hashed.
CREATE TABLE app_users (
    id               UUID          NOT NULL,
    user_code        VARCHAR(40)   NOT NULL,
    display_name     VARCHAR(120)  NOT NULL,
    pin_hash         VARCHAR(200)  NOT NULL,
    status           VARCHAR(10)   NOT NULL,
    failed_attempts  INTEGER       DEFAULT 0 NOT NULL,
    locked_until     TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT        NOT NULL,
    CONSTRAINT pk_app_users PRIMARY KEY (id),
    CONSTRAINT uq_app_users_code UNIQUE (user_code)
);

-- A signed-in device. Only a hash of the session token is kept.
CREATE TABLE device_sessions (
    id          UUID          NOT NULL,
    user_id     UUID          NOT NULL,
    token_hash  VARCHAR(64)   NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_device_sessions PRIMARY KEY (id),
    CONSTRAINT uq_device_sessions_token UNIQUE (token_hash),
    CONSTRAINT fk_device_sessions_user FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE
);
CREATE INDEX ix_device_sessions_user ON device_sessions (user_id);

-- Who is picking a task, the outbound container they pick into, and where it was dropped.
ALTER TABLE pick_tasks ADD COLUMN assigned_to UUID;
ALTER TABLE pick_tasks ADD COLUMN to_container_id UUID;
ALTER TABLE pick_tasks ADD COLUMN drop_location_id UUID;
ALTER TABLE pick_tasks ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE pick_tasks ADD COLUMN picked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE pick_tasks ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE pick_tasks ADD CONSTRAINT fk_pick_tasks_assigned_to FOREIGN KEY (assigned_to) REFERENCES app_users (id);
ALTER TABLE pick_tasks ADD CONSTRAINT fk_pick_tasks_to_container FOREIGN KEY (to_container_id) REFERENCES containers (id);
ALTER TABLE pick_tasks ADD CONSTRAINT fk_pick_tasks_drop_location FOREIGN KEY (drop_location_id) REFERENCES locations (id);
CREATE INDEX ix_pick_tasks_assigned_to ON pick_tasks (assigned_to);

-- What was picked. allocation_id is the stock still to pick; picked_allocation_id is the
-- same reservation after the stock moved into the outbound container.
ALTER TABLE pick_task_details ADD COLUMN picked_quantity INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE pick_task_details ADD COLUMN picked_allocation_id UUID;
ALTER TABLE pick_task_details ADD COLUMN picked_by UUID;
ALTER TABLE pick_task_details ADD COLUMN picked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE pick_task_details ADD CONSTRAINT fk_pick_task_details_picked_allocation
    FOREIGN KEY (picked_allocation_id) REFERENCES allocations (id) ON DELETE SET NULL;
ALTER TABLE pick_task_details ADD CONSTRAINT fk_pick_task_details_picked_by
    FOREIGN KEY (picked_by) REFERENCES app_users (id);
ALTER TABLE pick_task_details ADD CONSTRAINT ck_pick_task_details_picked
    CHECK (picked_quantity >= 0 AND picked_quantity <= quantity);
CREATE INDEX ix_pick_task_details_picked_allocation ON pick_task_details (picked_allocation_id);

ALTER TABLE order_lines ADD COLUMN picked_quantity INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE order_lines ADD CONSTRAINT ck_order_lines_picked
    CHECK (picked_quantity >= 0 AND picked_quantity <= allocated_quantity);
