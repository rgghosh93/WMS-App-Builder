package com.wms.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** The one row of settings for inventory control. */
@Entity
@Table(name = "inventory_settings")
public class InventorySettings {

    static final int ID = 1;

    @Id
    private int id;

    /** Changes of more eaches than this need a second person. */
    @Column(name = "approval_limit", nullable = false)
    private int approvalLimit;

    @Version
    private long version;

    protected InventorySettings() {
        // for JPA
    }

    public int getApprovalLimit() { return approvalLimit; }
    void setApprovalLimit(int approvalLimit) { this.approvalLimit = approvalLimit; }
}
