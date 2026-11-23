package com.wms.control;

import java.util.UUID;

import com.wms.common.ActiveStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Why stock was adjusted, held or released. */
@Entity
@Table(name = "reason_codes")
public class ReasonCode {

    public enum Kind { ADJUSTMENT, HOLD }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status;

    @Version
    private long version;

    protected ReasonCode() {
        // for JPA
    }

    ReasonCode(UUID id, Kind kind) {
        this.id = id;
        this.kind = kind;
    }

    public UUID getId() { return id; }
    public Kind getKind() { return kind; }
    public String getCode() { return code; }
    void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    void setName(String name) { this.name = name; }
    public ActiveStatus getStatus() { return status; }
    void setStatus(ActiveStatus status) { this.status = status; }
    public long getVersion() { return version; }
}
