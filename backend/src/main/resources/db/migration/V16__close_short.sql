-- How much of a line will never be shipped, because the order was closed short.
ALTER TABLE order_lines ADD COLUMN closed_quantity INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE order_lines ADD CONSTRAINT ck_order_lines_closed
    CHECK (closed_quantity >= 0 AND allocated_quantity + closed_quantity <= ordered_quantity);
