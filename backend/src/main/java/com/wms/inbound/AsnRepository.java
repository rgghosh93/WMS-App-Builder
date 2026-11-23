package com.wms.inbound;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface AsnRepository extends JpaRepository<Asn, UUID>, JpaSpecificationExecutor<Asn> {

    Optional<Asn> findByOwnerIdAndAsnNumber(UUID ownerId, String asnNumber);

    /** Several owners may have an ASN with the same number. */
    List<Asn> findAllByAsnNumber(String asnNumber);

    /** Loads and locks an ASN, so people receiving it at once take turns. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Asn a where a.id = :id")
    Optional<Asn> findLocked(UUID id);

    /** ASNs that can be received, soonest first. */
    @Query("select a from Asn a where a.status in (com.wms.inbound.AsnStatus.EXPECTED, com.wms.inbound.AsnStatus.RECEIVING) "
            + "order by a.expectedDate asc nulls last, a.asnNumber asc")
    List<Asn> findOpen();

    /** Which of these LPNs are announced on an ASN that is still open. */
    @Query("select distinct l.lpnId from AsnLine l where l.lpnId in :lpnIds and l.asn.status in "
            + "(com.wms.inbound.AsnStatus.EXPECTED, com.wms.inbound.AsnStatus.RECEIVING)")
    List<String> findAnnouncedLpns(Collection<String> lpnIds);

    @Query("select count(l) from AsnLine l where l.item.id = :itemId")
    long countLinesByItem(UUID itemId);

    @Query(value = "select nextval('asn_number_seq')", nativeQuery = true)
    long nextNumber();

    /** ASNs still open that announce an LPN. */
    @Query("select l.asn from AsnLine l where l.lpnId = :lpnId and l.asn.status in "
            + "(com.wms.inbound.AsnStatus.EXPECTED, com.wms.inbound.AsnStatus.RECEIVING)")
    List<Asn> findAnnouncing(String lpnId);

    @Query(value = "select nextval('blind_receipt_seq')", nativeQuery = true)
    long nextBlindNumber();

    @Query(value = "select nextval('inbound_lpn_seq')", nativeQuery = true)
    long nextLpn();
}
