package com.wms.outbound;

import java.util.UUID;

import com.wms.facility.FacilityRecord;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** How much of one packing detail is in a carton. */
@Entity
@Table(name = "pack_carton_lines")
public class PackCartonLine extends FacilityRecord {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carton_id", nullable = false)
    private PackCarton carton;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pack_task_detail_id", nullable = false)
    private PackTaskDetail detail;

    /** The order's reservation of the stock in the carton. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocation_id")
    private Allocation allocation;

    @Column(nullable = false)
    private int quantity;

    protected PackCartonLine() {
        // for JPA
    }

    PackCartonLine(UUID id, PackCarton carton, PackTaskDetail detail, Allocation allocation, int quantity) {
        this.id = id;
        this.carton = carton;
        this.detail = detail;
        this.allocation = allocation;
        this.quantity = quantity;
    }

    void add(int eaches) {
        quantity += eaches;
    }

    void clearAllocation() {
        allocation = null;
    }

    public UUID getId() { return id; }
    public PackTaskDetail getDetail() { return detail; }
    public Allocation getAllocation() { return allocation; }
    public int getQuantity() { return quantity; }
}
