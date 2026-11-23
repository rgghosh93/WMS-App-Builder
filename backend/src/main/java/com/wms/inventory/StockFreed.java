package com.wms.inventory;

import java.util.UUID;

/**
 * There may be more of an item for orders than there was: it was put away, moved, released, counted or corrected, or
 * an order let go of it. Published within the transaction that does it.
 */
public record StockFreed(UUID facilityId, UUID itemId) {
}
