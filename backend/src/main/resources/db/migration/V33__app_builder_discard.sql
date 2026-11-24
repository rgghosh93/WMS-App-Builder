-- A change request can be closed without going anywhere, and reopened.
ALTER TABLE builder_changes ADD COLUMN discarded_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE builder_changes ADD COLUMN discarded_by VARCHAR(40);
ALTER TABLE builder_changes ADD COLUMN discard_reason VARCHAR(500);
