package com.wms.masterdata.owner;

import java.util.UUID;

import com.wms.common.ActiveStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Whose stock it is: the company itself, or a client it keeps stock for. */
@Entity
@Table(name = "owners")
public class Owner {

    /** The owner every installation starts with, and what is meant where none is said. */
    public static final UUID FIRST = UUID.fromString("00000000-0000-4000-8003-000000000001");

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    /** Nothing new for an inactive one. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    @Version
    private long version;

    protected Owner() {
        // for JPA
    }

    Owner(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    void setName(String name) { this.name = name; }
    public ActiveStatus getStatus() { return status; }
    void setStatus(ActiveStatus status) { this.status = status; }
    public long getVersion() { return version; }
}
