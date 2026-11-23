CREATE TABLE work_control (
    id  INTEGER NOT NULL,
    CONSTRAINT pk_work_control PRIMARY KEY (id)
);
INSERT INTO work_control (id) VALUES (1);

-- How urgent each kind of work is unless told otherwise, and whether people are offered it as their next task.
CREATE TABLE work_settings (
    kind      VARCHAR(16)  NOT NULL,
    priority  INTEGER,
    offered   BOOLEAN      NOT NULL,
    CONSTRAINT pk_work_settings PRIMARY KEY (kind)
);
INSERT INTO work_settings (kind, priority, offered) VALUES
    ('PUTAWAY', 5, TRUE),
    ('REPLENISHMENT', 5, TRUE),
    ('COUNT', 7, TRUE),
    ('PICK', NULL, TRUE),
    ('PACK', NULL, FALSE);

-- What was said about a task: its priority, who is to do it, and who was just offered it.
CREATE TABLE task_directions (
    task_id      UUID         NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    priority     INTEGER,
    assigned_to  UUID,
    claimed_by   UUID,
    claimed_at   TIMESTAMP WITH TIME ZONE,
    version      BIGINT       NOT NULL,
    CONSTRAINT pk_task_directions PRIMARY KEY (task_id),
    CONSTRAINT fk_task_directions_user FOREIGN KEY (assigned_to) REFERENCES app_users (id) ON DELETE SET NULL,
    CONSTRAINT ck_task_directions_priority CHECK (priority BETWEEN 1 AND 9)
);
CREATE INDEX ix_task_directions_user ON task_directions (assigned_to);
CREATE INDEX ix_task_directions_claimed ON task_directions (claimed_by);

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission FROM roles r CROSS JOIN (VALUES ('TASKS'), ('DEVICE_NEXT')) AS p (permission)
WHERE r.id = CAST('00000000-0000-4000-8000-000000000002' AS UUID);
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, 'DEVICE_NEXT' FROM roles r WHERE r.id = CAST('00000000-0000-4000-8000-000000000003' AS UUID);
