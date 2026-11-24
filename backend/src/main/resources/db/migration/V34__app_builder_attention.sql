-- What a change request needs from the person who asked for it, until they look at it: its finished work, answers to
-- the agent's questions, or a look at why the agent stopped.
ALTER TABLE builder_changes ADD COLUMN attention VARCHAR(20);
ALTER TABLE builder_changes ADD COLUMN attention_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE builder_changes ADD CONSTRAINT ck_builder_changes_attention CHECK (attention IN ('FINISHED', 'QUESTIONS', 'PROBLEM'));
CREATE INDEX ix_builder_changes_attention ON builder_changes (requested_by, attention_at);
