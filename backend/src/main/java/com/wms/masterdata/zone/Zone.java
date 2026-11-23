package com.wms.masterdata.zone;

import java.util.UUID;

import com.wms.common.ActiveStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A grouping of storage locations for one function. Not the zone in a location's address. */
@Entity
@Table(name = "zones")
public class Zone {

    /** What locations in no allocation zone, or in one without a priority, count as. */
    public static final int LAST = 1000;

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "zone_function", nullable = false, length = 16)
    private ZoneFunction function;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /** An inactive zone is as if it weren't there. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    /** For allocation: lower is used first. */
    private Integer priority;

    /** For allocation: whether orders get stock from it at all. */
    @Column(nullable = false)
    private boolean allocatable = true;

    @Version
    private long version;

    protected Zone() {
        // for JPA
    }

    Zone(UUID id, ZoneFunction function) {
        this.id = id;
        this.function = function;
    }

    public UUID getId() { return id; }
    public ZoneFunction getFunction() { return function; }
    public String getCode() { return code; }
    void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    void setName(String name) { this.name = name; }
    public ActiveStatus getStatus() { return status; }
    void setStatus(ActiveStatus status) { this.status = status; }
    public Integer getPriority() { return priority; }
    void setPriority(Integer priority) { this.priority = priority; }
    public boolean isAllocatable() { return allocatable; }
    void setAllocatable(boolean allocatable) { this.allocatable = allocatable; }
    public long getVersion() { return version; }
}
