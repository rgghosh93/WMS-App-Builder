-- A change request can be accepted into the environment changes are tried out in, before it is released to the base WMS.
ALTER TABLE builder_changes DROP CONSTRAINT ck_builder_changes_status;
ALTER TABLE builder_changes ADD CONSTRAINT ck_builder_changes_status
    CHECK (status IN ('DRAFTING', 'ACCEPTED', 'IN_REVIEW', 'RELEASED', 'DISCARDED'));
ALTER TABLE builder_changes ADD COLUMN accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE builder_changes ADD COLUMN accepted_by VARCHAR(40);
-- The change's own commit that was accepted, so later work on it shows as not accepted yet.
ALTER TABLE builder_changes ADD COLUMN accepted_commit VARCHAR(40);
