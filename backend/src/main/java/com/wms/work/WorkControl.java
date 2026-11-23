package com.wms.work;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single row to lock while directing tasks, so two people aren't given the same one. */
@Entity
@Table(name = "work_control")
public class WorkControl {

    @Id
    private int id;

    protected WorkControl() {
        // for JPA
    }
}
