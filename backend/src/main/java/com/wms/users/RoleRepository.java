package com.wms.users;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    @Query("select r from Role r order by r.administrator desc, r.name asc")
    List<Role> findAllInOrder();

    Optional<Role> findFirstByNameIgnoreCase(String name);

    /** Locks the administrator role, so changes to who may manage users happen one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.administrator = true")
    List<Role> lockAdministrators();

    @Query("select count(u) from AppUser u join u.roles r where r.id = :roleId")
    long countUsers(UUID roleId);
}
