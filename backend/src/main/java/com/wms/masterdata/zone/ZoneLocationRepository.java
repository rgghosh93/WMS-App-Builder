package com.wms.masterdata.zone;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.wms.masterdata.location.Location;

public interface ZoneLocationRepository extends JpaRepository<ZoneLocation, UUID> {

    /** Which active zone of a function each of these locations is in. */
    @Query("""
            select zl from ZoneLocation zl join fetch zl.zone z where zl.function = :function and zl.location.id in :locationIds
            and z.status = com.wms.common.ActiveStatus.ACTIVE""")
    List<ZoneLocation> findActive(ZoneFunction function, Collection<UUID> locationIds);

    /** Zones of a function these locations are in, active or not. */
    @EntityGraph(attributePaths = "zone")
    List<ZoneLocation> findByFunctionAndLocation_IdIn(ZoneFunction function, Collection<UUID> locationIds);

    @EntityGraph(attributePaths = "zone")
    List<ZoneLocation> findByLocation_IdOrderByFunctionAsc(UUID locationId);

    /** The storage locations in a zone, in walking order. */
    @Query("""
            select l from ZoneLocation zl join zl.location l where zl.zone.id = :zoneId
            and l.type = com.wms.masterdata.location.LocationType.STORAGE order by l.locationId""")
    List<Location> findStorage(UUID zoneId);

    @Query(value = """
            select l from ZoneLocation zl join zl.location l where zl.zone.id = :zoneId
            and (:q = '%' or lower(l.locationId) like :q escape '\\') order by l.locationId""",
            countQuery = """
            select count(l) from ZoneLocation zl join zl.location l where zl.zone.id = :zoneId
            and (:q = '%' or lower(l.locationId) like :q escape '\\')""")
    Page<Location> search(UUID zoneId, String q, Pageable page);

    /** Locations in each zone: zone id, count. */
    @Query("select zl.zone.id, count(zl) from ZoneLocation zl group by zl.zone.id")
    List<Object[]> countByZone();

    long countByZone_Id(UUID zoneId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ZoneLocation zl where zl.zone.id = :zoneId and zl.location.id in :locationIds")
    int remove(UUID zoneId, Collection<UUID> locationIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ZoneLocation zl where zl.zone.id = :zoneId")
    void removeAll(UUID zoneId);
}
