package com.wms.builder;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Steps are named by the part of the WMS they were in, in the words people use. */
class WmsAreasTest {

    @Test
    void codeIsNamedByThePartOfTheWmsItIsIn() {
        assertThat(WmsAreas.of("backend/src/main/java/com/wms/masterdata/location/LocationType.java")).isEqualTo("locations");
        assertThat(WmsAreas.of("frontend/src/pages/rf/MoveTransaction.tsx")).isEqualTo("handheld screens");
        assertThat(WmsAreas.of("backend/src/main/java/com/wms/putaway/PutawayService.java")).isEqualTo("put-away");
        assertThat(WmsAreas.of("backend/src/main/java/com/wms/control/CountService.java")).isEqualTo("cycle counts");
        assertThat(WmsAreas.of("backend/src/main/java/com/wms/outbound/OrderAllocation.java")).isEqualTo("orders");
        assertThat(WmsAreas.of("backend/src/main/resources/db/migration/V1001__staging.sql")).isEqualTo("how information is stored");
        assertThat(WmsAreas.of("frontend/src/components/Field.tsx")).isEqualTo("screens");
        assertThat(WmsAreas.of("backend/src/main/java/com/wms/control/MoveService.java")).isEqualTo("stock moves");
        assertThat(WmsAreas.of("backend/src/main/java/com/wms/work/TaskQueue.java")).isEqualTo("the task queue");
        assertThat(WmsAreas.of("README.md")).isNull();
        assertThat(WmsAreas.of(null)).isNull();
    }
}
