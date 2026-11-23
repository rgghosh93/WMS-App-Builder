-- Buildings the warehouse is run in. Locations, stock, orders and work belong to one; items and other master data are shared.
CREATE TABLE facilities (
    id          UUID          NOT NULL,
    code        VARCHAR(20)   NOT NULL,
    name        VARCHAR(120)  NOT NULL,
    address     VARCHAR(400),
    status      VARCHAR(10)   NOT NULL,
    version     BIGINT        NOT NULL,
    CONSTRAINT pk_facilities PRIMARY KEY (id),
    CONSTRAINT uq_facilities_code UNIQUE (code)
);
INSERT INTO facilities (id, code, name, status, version) VALUES (CAST('00000000-0000-4000-8002-000000000001' AS UUID), 'MAIN', 'Main warehouse', 'ACTIVE', 0);

-- Whose stock it is.
CREATE TABLE owners (
    id          UUID          NOT NULL,
    code        VARCHAR(20)   NOT NULL,
    name        VARCHAR(120)  NOT NULL,
    status      VARCHAR(10)   NOT NULL,
    version     BIGINT        NOT NULL,
    CONSTRAINT pk_owners PRIMARY KEY (id),
    CONSTRAINT uq_owners_code UNIQUE (code)
);
INSERT INTO owners (id, code, name, status, version) VALUES (CAST('00000000-0000-4000-8003-000000000001' AS UUID), 'HOUSE', 'Our own stock', 'ACTIVE', 0);

ALTER TABLE inventory_adjustments ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE inventory_adjustments ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE inventory_adjustments ADD CONSTRAINT fk_inventory_adjustments_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_inventory_adjustments_facility ON inventory_adjustments (facility_id);
ALTER TABLE count_entries ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE count_entries ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE count_entries ADD CONSTRAINT fk_count_entries_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_count_entries_facility ON count_entries (facility_id);
ALTER TABLE count_results ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE count_results ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE count_results ADD CONSTRAINT fk_count_results_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_count_results_facility ON count_results (facility_id);
ALTER TABLE count_tasks ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE count_tasks ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE count_tasks ADD CONSTRAINT fk_count_tasks_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_count_tasks_facility ON count_tasks (facility_id);
ALTER TABLE asns ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE asns ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE asns ADD CONSTRAINT fk_asns_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_asns_facility ON asns (facility_id);
ALTER TABLE asn_events ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE asn_events ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE asn_events ADD CONSTRAINT fk_asn_events_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_asn_events_facility ON asn_events (facility_id);
ALTER TABLE asn_lines ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE asn_lines ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE asn_lines ADD CONSTRAINT fk_asn_lines_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_asn_lines_facility ON asn_lines (facility_id);
ALTER TABLE asn_receipts ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE asn_receipts ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE asn_receipts ADD CONSTRAINT fk_asn_receipts_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_asn_receipts_facility ON asn_receipts (facility_id);
ALTER TABLE inventory ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE inventory ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE inventory ADD CONSTRAINT fk_inventory_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_inventory_facility ON inventory (facility_id);
ALTER TABLE inventory_transactions ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE inventory_transactions ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE inventory_transactions ADD CONSTRAINT fk_inventory_transactions_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_inventory_transactions_facility ON inventory_transactions (facility_id);
ALTER TABLE containers ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE containers ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE containers ADD CONSTRAINT fk_containers_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_containers_facility ON containers (facility_id);
ALTER TABLE locations ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE locations ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE locations ADD CONSTRAINT fk_locations_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_locations_facility ON locations (facility_id);
ALTER TABLE zones ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE zones ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE zones ADD CONSTRAINT fk_zones_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_zones_facility ON zones (facility_id);
ALTER TABLE zone_locations ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE zone_locations ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE zone_locations ADD CONSTRAINT fk_zone_locations_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_zone_locations_facility ON zone_locations (facility_id);
ALTER TABLE allocations ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE allocations ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE allocations ADD CONSTRAINT fk_allocations_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_allocations_facility ON allocations (facility_id);
ALTER TABLE manifests ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE manifests ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE manifests ADD CONSTRAINT fk_manifests_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_manifests_facility ON manifests (facility_id);
ALTER TABLE order_events ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE order_events ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE order_events ADD CONSTRAINT fk_order_events_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_order_events_facility ON order_events (facility_id);
ALTER TABLE order_lines ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE order_lines ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE order_lines ADD CONSTRAINT fk_order_lines_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_order_lines_facility ON order_lines (facility_id);
ALTER TABLE outbound_orders ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE outbound_orders ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE outbound_orders ADD CONSTRAINT fk_outbound_orders_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_outbound_orders_facility ON outbound_orders (facility_id);
ALTER TABLE pack_cartons ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE pack_cartons ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE pack_cartons ADD CONSTRAINT fk_pack_cartons_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_pack_cartons_facility ON pack_cartons (facility_id);
ALTER TABLE pack_carton_lines ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE pack_carton_lines ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE pack_carton_lines ADD CONSTRAINT fk_pack_carton_lines_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_pack_carton_lines_facility ON pack_carton_lines (facility_id);
ALTER TABLE pack_tasks ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE pack_tasks ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE pack_tasks ADD CONSTRAINT fk_pack_tasks_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_pack_tasks_facility ON pack_tasks (facility_id);
ALTER TABLE pack_task_details ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE pack_task_details ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE pack_task_details ADD CONSTRAINT fk_pack_task_details_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_pack_task_details_facility ON pack_task_details (facility_id);
ALTER TABLE pick_tasks ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE pick_tasks ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE pick_tasks ADD CONSTRAINT fk_pick_tasks_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_pick_tasks_facility ON pick_tasks (facility_id);
ALTER TABLE pick_task_details ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE pick_task_details ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE pick_task_details ADD CONSTRAINT fk_pick_task_details_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_pick_task_details_facility ON pick_task_details (facility_id);
ALTER TABLE shipments ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE shipments ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE shipments ADD CONSTRAINT fk_shipments_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_shipments_facility ON shipments (facility_id);
ALTER TABLE putaway_rules ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE putaway_rules ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE putaway_rules ADD CONSTRAINT fk_putaway_rules_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_putaway_rules_facility ON putaway_rules (facility_id);
ALTER TABLE putaway_skips ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE putaway_skips ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE putaway_skips ADD CONSTRAINT fk_putaway_skips_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_putaway_skips_facility ON putaway_skips (facility_id);
ALTER TABLE putaway_tasks ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE putaway_tasks ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE putaway_tasks ADD CONSTRAINT fk_putaway_tasks_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_putaway_tasks_facility ON putaway_tasks (facility_id);
ALTER TABLE putaway_task_lines ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE putaway_task_lines ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE putaway_task_lines ADD CONSTRAINT fk_putaway_task_lines_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_putaway_task_lines_facility ON putaway_task_lines (facility_id);
ALTER TABLE replenishment_rules ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE replenishment_rules ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE replenishment_rules ADD CONSTRAINT fk_replenishment_rules_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_replenishment_rules_facility ON replenishment_rules (facility_id);
ALTER TABLE replenishment_tasks ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE replenishment_tasks ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE replenishment_tasks ADD CONSTRAINT fk_replenishment_tasks_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_replenishment_tasks_facility ON replenishment_tasks (facility_id);
ALTER TABLE task_directions ADD COLUMN facility_id UUID DEFAULT CAST('00000000-0000-4000-8002-000000000001' AS UUID) NOT NULL;
ALTER TABLE task_directions ALTER COLUMN facility_id DROP DEFAULT;
ALTER TABLE task_directions ADD CONSTRAINT fk_task_directions_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
CREATE INDEX ix_task_directions_facility ON task_directions (facility_id);

-- What was unique in the warehouse is unique in a facility.
ALTER TABLE locations DROP CONSTRAINT uq_locations_location_id;
ALTER TABLE locations ADD CONSTRAINT uq_locations_location_id UNIQUE (facility_id, location_id);
ALTER TABLE locations DROP CONSTRAINT uq_locations_identifiers;
ALTER TABLE locations ADD CONSTRAINT uq_locations_identifiers
    UNIQUE (facility_id, location_type, area_code, zone_code, aisle_code, bay_code, level_code, position_code);
ALTER TABLE containers DROP CONSTRAINT uq_containers_lpn_id;
ALTER TABLE containers ADD CONSTRAINT uq_containers_lpn_id UNIQUE (facility_id, lpn_id);
ALTER TABLE zones DROP CONSTRAINT uq_zones_code;
ALTER TABLE zones ADD CONSTRAINT uq_zones_code UNIQUE (facility_id, zone_function, code);

-- Items, orders and ASNs have an owner; so stock does, through its item.
ALTER TABLE items ADD COLUMN owner_id UUID DEFAULT CAST('00000000-0000-4000-8003-000000000001' AS UUID) NOT NULL;
ALTER TABLE items ALTER COLUMN owner_id DROP DEFAULT;
ALTER TABLE items ADD CONSTRAINT fk_items_owner FOREIGN KEY (owner_id) REFERENCES owners (id);
ALTER TABLE items DROP CONSTRAINT uq_items_item_id;
ALTER TABLE items ADD CONSTRAINT uq_items_item_id UNIQUE (owner_id, item_id);
ALTER TABLE items DROP CONSTRAINT uq_items_barcode;
ALTER TABLE items ADD CONSTRAINT uq_items_barcode UNIQUE (owner_id, barcode);
CREATE INDEX ix_items_item_id ON items (item_id);
CREATE INDEX ix_items_barcode ON items (barcode);
ALTER TABLE item_uoms DROP CONSTRAINT uq_item_uoms_barcode;
ALTER TABLE item_uoms ADD CONSTRAINT uq_item_uoms_barcode UNIQUE (item_id, barcode);
CREATE INDEX ix_item_uoms_barcode ON item_uoms (barcode);
ALTER TABLE outbound_orders ADD COLUMN owner_id UUID DEFAULT CAST('00000000-0000-4000-8003-000000000001' AS UUID) NOT NULL;
ALTER TABLE outbound_orders ALTER COLUMN owner_id DROP DEFAULT;
ALTER TABLE outbound_orders ADD CONSTRAINT fk_outbound_orders_owner FOREIGN KEY (owner_id) REFERENCES owners (id);
ALTER TABLE outbound_orders DROP CONSTRAINT uq_outbound_orders_number;
ALTER TABLE outbound_orders ADD CONSTRAINT uq_outbound_orders_number UNIQUE (facility_id, owner_id, order_number);
ALTER TABLE asns ADD COLUMN owner_id UUID DEFAULT CAST('00000000-0000-4000-8003-000000000001' AS UUID) NOT NULL;
ALTER TABLE asns ALTER COLUMN owner_id DROP DEFAULT;
ALTER TABLE asns ADD CONSTRAINT fk_asns_owner FOREIGN KEY (owner_id) REFERENCES owners (id);
ALTER TABLE asns DROP CONSTRAINT uq_asns_number;
ALTER TABLE asns ADD CONSTRAINT uq_asns_number UNIQUE (facility_id, owner_id, asn_number);
ALTER TABLE inventory_transactions ADD COLUMN owner_code VARCHAR(20) DEFAULT 'HOUSE' NOT NULL;
ALTER TABLE inventory_transactions ALTER COLUMN owner_code DROP DEFAULT;

-- Where people work: everywhere if nothing is said. And where they are now.
CREATE TABLE user_facilities (
    user_id      UUID NOT NULL,
    facility_id  UUID NOT NULL,
    CONSTRAINT pk_user_facilities PRIMARY KEY (user_id, facility_id),
    CONSTRAINT fk_user_facilities_user FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_facilities_facility FOREIGN KEY (facility_id) REFERENCES facilities (id)
);
ALTER TABLE app_users ADD COLUMN last_facility_id UUID;
ALTER TABLE app_users ADD CONSTRAINT fk_app_users_facility FOREIGN KEY (last_facility_id) REFERENCES facilities (id);
ALTER TABLE device_sessions ADD COLUMN facility_id UUID;
ALTER TABLE device_sessions ADD CONSTRAINT fk_device_sessions_facility FOREIGN KEY (facility_id) REFERENCES facilities (id);
