package com.wms.control;

import java.util.UUID;

import com.wms.masterdata.container.Container;
import com.wms.masterdata.item.Item;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** What was counted against what was expected. */
@Entity
@Table(name = "count_results")
public class CountResult {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private CountTask task;

    @Column(name = "container_id")
    private UUID containerId;

    @Column(name = "lpn_id", length = 40)
    private String lpnId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "lot_number", length = 40)
    private String lotNumber;

    @Column(name = "serial_number", length = 60)
    private String serialNumber;

    @Column(nullable = false)
    private int expected;

    @Column(nullable = false)
    private int counted;

    protected CountResult() {
        // for JPA
    }

    CountResult(CountTask task, Container container, Item item, String lotNumber, String serialNumber, int expected, int counted) {
        this.id = UUID.randomUUID();
        this.task = task;
        this.containerId = container == null ? null : container.getId();
        this.lpnId = container == null ? null : container.getLpnId();
        this.item = item;
        this.lotNumber = lotNumber;
        this.serialNumber = serialNumber;
        this.expected = expected;
        this.counted = counted;
    }

    int difference() {
        return counted - expected;
    }

    public UUID getContainerId() { return containerId; }
    public String getLpnId() { return lpnId; }
    public Item getItem() { return item; }
    public String getLotNumber() { return lotNumber; }
    public String getSerialNumber() { return serialNumber; }
    public int getExpected() { return expected; }
    public int getCounted() { return counted; }
}
