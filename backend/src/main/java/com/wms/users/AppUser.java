package com.wms.users;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.OptimisticLock;

import com.wms.common.ActiveStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Someone who signs in to mobile devices with a PIN, to the desktop with a password, or both. */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    private UUID id;

    @Column(name = "user_code", nullable = false, length = 40)
    private String userCode;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "pin_hash", length = 200)
    private String pinHash;

    @Column(name = "password_hash", length = 200)
    private String passwordHash;

    /** Someone else set the password, so it only serves to choose one's own. */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @ManyToMany
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    // Signing in doesn't count as a change to the user, so it doesn't get in the way of someone editing them.
    @OptimisticLock(excluded = true)
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @OptimisticLock(excluded = true)
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @OptimisticLock(excluded = true)
    @Column(name = "last_sign_in_at")
    private Instant lastSignInAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OptimisticLock(excluded = true)
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

    void recordSuccess(Instant now) {
        unlock();
        lastSignInAt = now;
    }

    /** Forgets wrong PINs, and lets a locked out user try again. */
    void unlock() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    void setPassword(String hash, boolean mustChange) {
        passwordHash = hash;
        mustChangePassword = mustChange;
        unlock();
    }

    void setRoles(Collection<Role> wanted) {
        roles.retainAll(wanted);
        roles.addAll(wanted);
    }

    /** Everything their roles let them do. */
    public Set<Permission> permissions() {
        Set<Permission> all = EnumSet.noneOf(Permission.class);
        roles.forEach(r -> all.addAll(r.getPermissions()));
        return all;
    }

    void setPin(String hash) {
        pinHash = hash;
        unlock();
    }

    public UUID getId() { return id; }
    public String getUserCode() { return userCode; }
    void setUserCode(String userCode) { this.userCode = userCode; }
    public String getDisplayName() { return displayName; }
    void setDisplayName(String displayName) { this.displayName = displayName; }
    String getPinHash() { return pinHash; }
    String getPasswordHash() { return passwordHash; }
    boolean isMustChangePassword() { return mustChangePassword; }
    Set<Role> getRoles() { return roles; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
    Instant getLockedUntil() { return lockedUntil; }
    Instant getLastSignInAt() { return lastSignInAt; }
    long getVersion() { return version; }
}
