package com.wms.putaway;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single row to lock while choosing or filling a location. */
@Entity
@Table(name = "putaway_control")
public class PutawayControl {

    static final int ID = 1;

    @Id
    private int id;

    protected PutawayControl() {
        // for JPA
    }
}
