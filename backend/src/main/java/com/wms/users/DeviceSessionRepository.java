package com.wms.users;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeviceSessionRepository extends JpaRepository<DeviceSession, UUID> {

    @EntityGraph(attributePaths = {"user"})
    Optional<DeviceSession> findByTokenHashAndKind(String tokenHash, DeviceSession.Kind kind);

    @Modifying
    @Query("delete from DeviceSession s where s.user.id = :userId")
    int deleteByUser(UUID userId);

    @Modifying
    @Query("delete from DeviceSession s where s.user.id = :userId and s.tokenHash <> :tokenHash")
    void deleteOthers(UUID userId, String tokenHash);

    /** Devices each of these users is signed in on: user id, count. */
    @Query("select s.user.id, count(s) from DeviceSession s where s.user.id in :userIds and s.expiresAt > :now group by s.user.id")
    List<Object[]> countByUser(Collection<UUID> userIds, Instant now);

    @Modifying
    @Query("delete from DeviceSession s where s.expiresAt < :now")
    void deleteExpired(Instant now);
}
