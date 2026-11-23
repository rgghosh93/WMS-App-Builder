package com.wms.facility;

import java.util.UUID;

import com.wms.common.ActiveStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A building the warehouse is run in. */
@Entity
@Table(name = "facilities")
public class Facility {

    @Id
    private UUID id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 400)
    private String address;

    /** Nobody can work in an inactive one. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    @Version
    private long version;

    protected Facility() {
        // for JPA
    }

    Facility(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    void setAddress(String address) { this.address = address; }
    public ActiveStatus getStatus() { return status; }
    void setStatus(ActiveStatus status) { this.status = status; }
    public long getVersion() { return version; }
}
