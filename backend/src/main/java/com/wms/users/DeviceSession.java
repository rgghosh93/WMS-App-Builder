package com.wms.users;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One sign-in on one device, or on the desktop. Only a hash of its token is stored. */
@Entity
@Table(name = "device_sessions")
public class DeviceSession {

    enum Kind { DEVICE, DESKTOP }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind;

    /** Where they are working. */
    @Column(name = "facility_id")
    private UUID facilityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DeviceSession() {
        // for JPA
    }

    DeviceSession(AppUser user, Kind kind, String tokenHash, UUID facilityId, Instant createdAt, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.kind = kind;
        this.tokenHash = tokenHash;
        this.facilityId = facilityId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    UUID getFacilityId() { return facilityId; }
    void setFacilityId(UUID id) { facilityId = id; }
    AppUser getUser() { return user; }
    Instant getExpiresAt() { return expiresAt; }
}
