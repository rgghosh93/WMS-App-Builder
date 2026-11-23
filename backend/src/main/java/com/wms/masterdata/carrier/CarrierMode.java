package com.wms.masterdata.carrier;

/** How a carrier moves freight. */
public enum CarrierMode {
    /** Individual cartons, e.g. ground or express. */
    PARCEL,
    /** Less than a truckload. */
    LTL,
    /** Full truckload. */
    TRUCKLOAD,
    /** Our own or a customer's vehicle, including pickups. */
    OTHER
}
