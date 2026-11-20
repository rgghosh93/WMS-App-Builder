-- Containers: permanent, reusable containers in the facility (totes, ASRS bins,
-- pallets owned by the site...). Each is identified by its LPN and has a type.
CREATE TABLE containers (
    id                   UUID          NOT NULL,
    lpn_id               VARCHAR(40)   NOT NULL,
    container_type_id    UUID          NOT NULL,
    status               VARCHAR(12)   NOT NULL,
    current_location_id  UUID,
    notes                VARCHAR(500),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    version              BIGINT        NOT NULL,
    CONSTRAINT pk_containers PRIMARY KEY (id),
    CONSTRAINT uq_containers_lpn_id UNIQUE (lpn_id),
    CONSTRAINT fk_containers_container_type FOREIGN KEY (container_type_id) REFERENCES container_types (id),
    CONSTRAINT fk_containers_current_location FOREIGN KEY (current_location_id) REFERENCES locations (id)
);

CREATE INDEX ix_containers_container_type ON containers (container_type_id);
CREATE INDEX ix_containers_current_location ON containers (current_location_id);
CREATE INDEX ix_containers_status ON containers (status);
