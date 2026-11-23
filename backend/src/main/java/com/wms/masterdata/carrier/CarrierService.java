package com.wms.masterdata.carrier;

import java.util.UUID;

import com.wms.common.ActiveStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A level of service a carrier offers, such as Ground or 2-Day. */
@Entity
@Table(name = "carrier_services")
public class CarrierService {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "carrier_id", nullable = false)
    private Carrier carrier;

    @Column(name = "service_code", nullable = false, length = 20)
    private String serviceCode;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActiveStatus status = ActiveStatus.ACTIVE;

    protected CarrierService() {
        // for JPA
    }

    public CarrierService(UUID id, Carrier carrier, String serviceCode, String name) {
        this.id = id;
        this.carrier = carrier;
        this.serviceCode = serviceCode;
        this.name = name;
    }

    public UUID getId() { return id; }
    public Carrier getCarrier() { return carrier; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ActiveStatus getStatus() { return status; }
    public void setStatus(ActiveStatus status) { this.status = status; }
}
