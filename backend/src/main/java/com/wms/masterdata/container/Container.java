package com.wms.masterdata.container;

import java.time.Instant;
import java.util.UUID;

import com.wms.masterdata.location.Location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A permanent container in the facility, such as a tote or an ASRS bin, identified by its LPN. */
@Entity
@Table(name = "containers")
public class Container {

    @Id
    private UUID id;

    @Column(name = "lpn_id", nullable = false, length = 40)
    private String lpnId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_type_id", nullable = false)
    private ContainerType containerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ContainerStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_location_id")
    private Location currentLocation;

    /** The container this one sits inside, if any. A nested container has no location of its own. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_container_id")
    private Container parent;

    /** True for facility-owned containers (totes, bins); false for LPNs created with inventory. */
    @Column(nullable = false)
    private boolean permanent = true;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Container() {
        // for JPA
    }

    public Container(UUID id) {
        this.id = id;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getLpnId() { return lpnId; }
    public void setLpnId(String lpnId) { this.lpnId = lpnId; }
    public ContainerType getContainerType() { return containerType; }
    public void setContainerType(ContainerType containerType) { this.containerType = containerType; }
    public ContainerStatus getStatus() { return status; }
    public void setStatus(ContainerStatus status) { this.status = status; }
    public Location getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(Location currentLocation) { this.currentLocation = currentLocation; }
    public Container getParent() { return parent; }
    public void setParent(Container parent) { this.parent = parent; }
    public boolean isPermanent() { return permanent; }
    public void setPermanent(boolean permanent) { this.permanent = permanent; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
