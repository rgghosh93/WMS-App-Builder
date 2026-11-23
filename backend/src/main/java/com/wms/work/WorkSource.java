package com.wms.work;

import java.util.List;

/** Where tasks of one kind come from. Each area of the warehouse provides its own. */
public interface WorkSource {

    WorkKind kind();

    /** Tasks that aren't finished, waiting or under way. */
    List<WorkItem> active();
}
