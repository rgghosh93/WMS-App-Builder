package com.wms.masterdata.uom;

import java.time.Instant;
import java.util.UUID;

import com.wms.common.ActiveStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A unit that quantities are handled in, such as EA, CS or PL. Exactly one unit
 * is the base (EA); every other unit is a multiple of it, set per item.
 */
@Entity
@Table(name = "units_of_measure")
public class UnitOfMeasure {

    @Id
    private UUID id;

    @Column(name = "uom_code", nullable = false, length = 10)
    private String uomCode;

    @Column(nullable = false, length = 100)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private UomKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    @Column(nullable = false)
    private boolean base;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected UnitOfMeasure() {
        // for JPA
    }

    public UnitOfMeasure(UUID id) {
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
    public String getUomCode() { return uomCode; }
    public void setUomCode(String uomCode) { this.uomCode = uomCode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UomKind getKind() { return kind; }
    public void setKind(UomKind kind) { this.kind = kind; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    public boolean isBase() { return base; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
