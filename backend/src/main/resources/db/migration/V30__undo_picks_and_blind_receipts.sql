-- Picked orders can be cancelled or undone: what was to be packed for them is not packed any more.
ALTER TABLE pack_task_details ADD COLUMN cancelled_quantity INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE pack_task_details DROP CONSTRAINT ck_pack_task_details_quantities;
ALTER TABLE pack_task_details ADD CONSTRAINT ck_pack_task_details_quantities CHECK (
    quantity > 0 AND packed_quantity >= 0 AND short_quantity >= 0 AND cancelled_quantity >= 0
    AND packed_quantity + short_quantity + cancelled_quantity <= quantity);

-- Receiving what nobody announced. Its lines are made as it is received, so nothing is expected on them.
ALTER TABLE asns ADD COLUMN blind BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE asn_lines DROP CONSTRAINT ck_asn_lines_quantities;
ALTER TABLE asn_lines ADD CONSTRAINT ck_asn_lines_quantities CHECK (expected_quantity >= 0 AND received_quantity >= 0);
CREATE SEQUENCE blind_receipt_seq START WITH 1;
