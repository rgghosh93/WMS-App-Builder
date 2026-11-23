package com.wms.users;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A set of permissions. People can hold several roles and may do whatever any of them allows. */
@Entity
@Table(name = "roles")
public class Role {

    public static final UUID ADMINISTRATOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
    public static final UUID OPERATOR = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Id
    private UUID id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 250)
    private String description;

    /** Always has every permission, and can't be changed. */
    @Column(nullable = false)
    private boolean administrator;

    @ElementCollection
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = new HashSet<>();

    @Version
    private long version;

    protected Role() {
        // for JPA
    }

    Role(UUID id) {
        this.id = id;
    }

    public Set<Permission> getPermissions() {
        Set<Permission> all = administrator ? EnumSet.allOf(Permission.class) : EnumSet.noneOf(Permission.class);
        all.addAll(permissions);
        return all;
    }

    void setPermissions(Set<Permission> wanted) {
        permissions.retainAll(wanted);
        permissions.addAll(wanted);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    void setDescription(String description) { this.description = description; }
    public boolean isAdministrator() { return administrator; }
    long getVersion() { return version; }
}
