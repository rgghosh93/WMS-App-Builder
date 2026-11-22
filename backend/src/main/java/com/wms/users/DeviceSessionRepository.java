package com.wms.users;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeviceSessionRepository extends JpaRepository<DeviceSession, UUID> {

    @EntityGraph(attributePaths = {"user"})
    Optional<DeviceSession> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from DeviceSession s where s.expiresAt < :now")
    void deleteExpired(Instant now);
}
