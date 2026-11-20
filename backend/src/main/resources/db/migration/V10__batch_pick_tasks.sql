-- A pick task can now pick for several orders at once (batch picking). The order
-- belongs to each detail (it already did), not to the task.
ALTER TABLE pick_tasks DROP CONSTRAINT fk_pick_task_headers_order;
DROP INDEX ix_pick_task_headers_order;
ALTER TABLE pick_tasks DROP COLUMN order_id;

-- How the task is picked: DISCRETE (one order) or BATCH (several orders in one walk).
ALTER TABLE pick_tasks ADD COLUMN pick_method VARCHAR(16) DEFAULT 'DISCRETE' NOT NULL;
ALTER TABLE pick_tasks ADD CONSTRAINT ck_pick_tasks_method CHECK (pick_method IN ('DISCRETE', 'BATCH'));
