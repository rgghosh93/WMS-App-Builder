package com.wms.inventory;

import com.wms.masterdata.container.Container;

/** Something that has to know when an LPN is emptied by a correction (put-away). */
public interface EmptiedLpns {

    void emptied(Container lpn);
}
