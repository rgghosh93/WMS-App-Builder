package com.wms.masterdata.container;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ContainerRepository extends JpaRepository<Container, UUID>, JpaSpecificationExecutor<Container> {

    /** Lists containers with their type, location and parent loaded in the same query. */
    @Override
    @EntityGraph(attributePaths = {"containerType", "currentLocation", "parent"})
    Page<Container> findAll(Specification<Container> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"containerType", "currentLocation", "parent"})
    Optional<Container> findWithDetailsById(UUID id);

    /** Containers sitting directly in any of these locations (not nested ones). */
    @EntityGraph(attributePaths = {"containerType"})
    List<Container> findByCurrentLocation_IdIn(Collection<UUID> locationIds);

    /** Containers sitting directly inside any of these containers. */
    @EntityGraph(attributePaths = {"containerType"})
    List<Container> findByParent_IdIn(Collection<UUID> parentIds);

    long countByParent_Id(UUID parentId);

    Optional<Container> findByLpnId(String lpnId);

    /** Loads and locks a container, so people handling it at once take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Container c where c.lpnId = :lpnId")
    Optional<Container> findLockedByLpnId(String lpnId);

    @Query("select c.lpnId from Container c where c.lpnId in :lpnIds order by c.lpnId")
    List<String> findExistingLpnIds(Collection<String> lpnIds);

    long countByContainerType_Id(UUID containerTypeId);

    long countByCurrentLocation_Id(UUID locationId);
}
