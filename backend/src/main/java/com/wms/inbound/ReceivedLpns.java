package com.wms.inbound;

import com.wms.masterdata.container.Container;
import com.wms.masterdata.location.Location;

/** Something that has to know when stock is received into LPNs (put-away). */
public interface ReceivedLpns {

    /** Stock was received into an LPN, which stands at this receiving location or dock door. Called for every receipt. */
    void received(Container lpn, Location at);

    /** Everything received into an LPN was taken back. */
    void emptied(Container lpn);
}
