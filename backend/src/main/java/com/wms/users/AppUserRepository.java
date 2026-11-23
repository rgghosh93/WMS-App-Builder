package com.wms.users;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface AppUserRepository extends JpaRepository<AppUser, UUID>, JpaSpecificationExecutor<AppUser> {

    Optional<AppUser> findByUserCode(String userCode);

    /** Loads and locks a user, so wrong PINs tried at the same time are all counted. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.userCode = :userCode")
    Optional<AppUser> findLockedByUserCode(String userCode);

    /** Whether anyone active can sign in to the desktop and manage users. */
    @Query("""
            select count(u) > 0 from AppUser u where u.status = com.wms.common.ActiveStatus.ACTIVE and u.passwordHash is not null
            and exists (select 1 from AppUser a join a.roles r where a.id = u.id and (r.administrator = true
                or com.wms.users.Permission.USERS member of r.permissions))
            and exists (select 1 from AppUser b join b.roles r where b.id = u.id and (r.administrator = true
                or com.wms.users.Permission.DESKTOP member of r.permissions))""")
    boolean existsAdministrator();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findLocked(UUID id);
}
