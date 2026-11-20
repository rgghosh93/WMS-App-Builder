package com.wms.masterdata.uom;

/** The packaging level a unit of measure represents. */
public enum UomKind {
    /** A single unit. Reserved for the base unit (EA). */
    EACH,
    INNER_PACK,
    CASE,
    LAYER,
    PALLET,
    OTHER
}
