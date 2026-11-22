package com.wms.users;

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

/** Someone who signs in to a mobile device with a user ID and PIN. */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    private UUID id;

    @Column(name = "user_code", nullable = false, length = 40)
    private String userCode;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "pin_hash", nullable = false, length = 200)
    private String pinHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AppUser() {
        // for JPA
    }

    public AppUser(UUID id, String userCode, String displayName, String pinHash) {
        this.id = id;
        this.userCode = userCode;
        this.displayName = displayName;
        this.pinHash = pinHash;
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

    boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** Counts a wrong PIN; at the limit the user is locked out until the given time. */
    void recordFailure(int limit, Instant lockUntil) {
        failedAttempts++;
        if (failedAttempts >= limit) {
            lockedUntil = lockUntil;
            failedAttempts = 0;
        }
    }

    void recordSuccess() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    public UUID getId() { return id; }
    public String getUserCode() { return userCode; }
    public String getDisplayName() { return displayName; }
    String getPinHash() { return pinHash; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
}
