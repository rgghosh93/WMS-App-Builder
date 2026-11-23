package com.wms.replenishment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single row to lock while working out what to replenish, so nothing is planned twice. */
@Entity
@Table(name = "replenishment_control")
public class ReplenishmentControl {

    @Id
    private int id;

    protected ReplenishmentControl() {
        // for JPA
    }
}
