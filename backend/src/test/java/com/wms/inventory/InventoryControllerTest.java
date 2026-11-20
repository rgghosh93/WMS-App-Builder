package com.wms.inventory;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.wms.common.ActiveStatus;
import com.wms.common.measure.DimensionUom;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.container.ContainerCategory;
import com.wms.masterdata.container.ContainerRepository;
import com.wms.masterdata.container.ContainerStatus;
import com.wms.masterdata.container.ContainerType;
import com.wms.masterdata.container.ContainerTypeRepository;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.item.ItemRepository;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.masterdata.location.LocationType;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InventoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InventoryRepository inventory;

    @Autowired
    private ItemRepository items;

    @Autowired
    private LocationRepository locations;

    @Autowired
    private ContainerRepository containers;

    @Autowired
    private ContainerTypeRepository types;

    private Item bars;
    private Item drill;
    private Location shelf;
    private Location reserve;
    private Location empty;
    private Container pallet;
    private Container carton;

    /**
     * shelf   : 30 bars loose (lot L1) + 5 bars loose (lot L2)
     * reserve : pallet P1 holding 3 bars directly and carton C1, which holds 2 serial drills
     * empty   : nothing
     */
    @BeforeEach
    void fixtures() {
        bars = item("SKU-BAR");
        drill = item("SKU-DRILL");
        shelf = location("DC1-BK-01-01-A-01", "BK");
        reserve = location("DC1-RS-01-01-A-01", "RS");
        empty = location("DC1-RS-01-01-A-02", "RS");
        ContainerType palletType = type("PLT", ContainerCategory.PALLET);
        ContainerType cartonType = type("CTN", ContainerCategory.CARTON);

        pallet = container("P1", palletType);
        pallet.setCurrentLocation(reserve);
        containers.save(pallet);
        carton = container("C1", cartonType);
        carton.setParent(pallet);
        containers.save(carton);

        stock(bars, 30, "L1", null, shelf, null);
        stock(bars, 5, "L2", null, shelf, null);
        stock(bars, 3, "L1", null, null, pallet);
        stock(drill, 1, null, "SN-1", null, carton);
        stock(drill, 1, null, "SN-2", null, carton);
    }

    @Test
    void listsOccupiedLocationsWithTotalsIncludingNestedContainers() throws Exception {
        mvc.perform(get("/api/inventory/locations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].location.locationId").value("DC1-BK-01-01-A-01"))
                .andExpect(jsonPath("$.content[0].totalQuantity").value(35))
                .andExpect(jsonPath("$.content[0].itemCount").value(1))
                .andExpect(jsonPath("$.content[0].containerCount").value(0))
                .andExpect(jsonPath("$.content[0].looseLineCount").value(2))
                .andExpect(jsonPath("$.content[1].location.locationId").value("DC1-RS-01-01-A-01"))
                .andExpect(jsonPath("$.content[1].totalQuantity").value(5))
                .andExpect(jsonPath("$.content[1].itemCount").value(2))
                .andExpect(jsonPath("$.content[1].containerCount").value(2))
                .andExpect(jsonPath("$.content[1].looseLineCount").value(0));
    }

    @Test
    void canIncludeEmptyLocationsAndFilter() throws Exception {
        mvc.perform(get("/api/inventory/locations").param("occupied", "false"))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/inventory/locations").param("zone", "rs").param("occupied", "false"))
                .andExpect(jsonPath("$.content", hasSize(2)));
        mvc.perform(get("/api/inventory/locations").param("q", "bk-01"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].location.zone").value("BK"));
    }

    @Test
    void showsLooseStockWithAttributes() throws Exception {
        mvc.perform(get("/api/inventory/locations/" + shelf.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuantity").value(35))
                .andExpect(jsonPath("$.containers", hasSize(0)))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].item.itemId").value("SKU-BAR"))
                .andExpect(jsonPath("$.lines[0].lotNumber").value("L1"))
                .andExpect(jsonPath("$.lines[0].quantity").value(30))
                .andExpect(jsonPath("$.lines[0].expiryDate").value("2027-04-15"))
                .andExpect(jsonPath("$.lines[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.lines[1].lotNumber").value("L2"));
    }

    @Test
    void showsContainersInsideContainers() throws Exception {
        mvc.perform(get("/api/inventory/locations/" + reserve.getId()))
                .andExpect(jsonPath("$.totalQuantity").value(5))
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.containers", hasSize(1)))
                .andExpect(jsonPath("$.containers[0].lpnId").value("P1"))
                .andExpect(jsonPath("$.containers[0].permanent").value(false))
                .andExpect(jsonPath("$.containers[0].containerType.category").value("PALLET"))
                .andExpect(jsonPath("$.containers[0].totalQuantity").value(5))
                .andExpect(jsonPath("$.containers[0].itemCount").value(2))
                .andExpect(jsonPath("$.containers[0].lines[0].quantity").value(3))
                .andExpect(jsonPath("$.containers[0].children", hasSize(1)))
                .andExpect(jsonPath("$.containers[0].children[0].lpnId").value("C1"))
                .andExpect(jsonPath("$.containers[0].children[0].totalQuantity").value(2))
                .andExpect(jsonPath("$.containers[0].children[0].lines[0].serialNumber").value("SN-1"))
                .andExpect(jsonPath("$.containers[0].children[0].lines[1].serialNumber").value("SN-2"));
    }

    @Test
    void emptyLocationHasNothing() throws Exception {
        mvc.perform(get("/api/inventory/locations/" + empty.getId()))
                .andExpect(jsonPath("$.totalQuantity").value(0))
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.containers", hasSize(0)));
    }

    @Test
    void unknownLocationIsNotFound() throws Exception {
        mvc.perform(get("/api/inventory/locations/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void blocksDeletingThingsThatHoldStock() throws Exception {
        mvc.perform(delete("/api/locations/" + shelf.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("2 inventory records are in it")));
        mvc.perform(delete("/api/containers/" + pallet.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("1 container is inside it")))
                .andExpect(jsonPath("$.detail", containsString("it holds 1 inventory record")));
        mvc.perform(delete("/api/items/" + bars.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("38 on hand")));
    }

    @Test
    void listsContainersWithStockIncludingNestedOnes() throws Exception {
        Container emptyTote = container("TOTE1", type("TOTE", ContainerCategory.TOTE));
        emptyTote.setPermanent(true);
        emptyTote.setCurrentLocation(empty);
        containers.save(emptyTote);

        mvc.perform(get("/api/inventory/containers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].lpnId").value("C1"))
                .andExpect(jsonPath("$.content[0].location.locationId").value("DC1-RS-01-01-A-01"))
                .andExpect(jsonPath("$.content[0].path[0].lpnId").value("P1"))
                .andExpect(jsonPath("$.content[0].totalQuantity").value(2))
                .andExpect(jsonPath("$.content[0].lineCount").value(2))
                .andExpect(jsonPath("$.content[0].nestedCount").value(0))
                .andExpect(jsonPath("$.content[1].lpnId").value("P1"))
                .andExpect(jsonPath("$.content[1].path", hasSize(0)))
                .andExpect(jsonPath("$.content[1].containerType.category").value("PALLET"))
                .andExpect(jsonPath("$.content[1].totalQuantity").value(5))
                .andExpect(jsonPath("$.content[1].itemCount").value(2))
                .andExpect(jsonPath("$.content[1].nestedCount").value(1))
                .andExpect(jsonPath("$.content[1].lineCount").value(1));

        mvc.perform(get("/api/inventory/containers").param("withStock", "false"))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/inventory/containers").param("withStock", "false").param("permanent", "true"))
                .andExpect(jsonPath("$.content[0].lpnId").value("TOTE1"));
        mvc.perform(get("/api/inventory/containers").param("category", "CARTON"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].lpnId").value("C1"));
        mvc.perform(get("/api/inventory/containers").param("q", "p"))
                .andExpect(jsonPath("$.content[0].lpnId").value("P1"));
    }

    @Test
    void findsContainersWhoseStockIsSeveralLevelsDown() throws Exception {
        Container outer = container("OUTER", type("CAGE", ContainerCategory.CAGE));
        outer.setCurrentLocation(empty);
        containers.save(outer);
        Container middle = container("MIDDLE", type("BOX", ContainerCategory.CASE));
        middle.setParent(outer);
        containers.save(middle);
        Container inner = container("INNER", type("BAG", ContainerCategory.OTHER));
        inner.setParent(middle);
        containers.save(inner);
        stock(bars, 7, "L9", null, null, inner);

        mvc.perform(get("/api/inventory/containers").param("q", "OUTER"))
                .andExpect(jsonPath("$.content[0].totalQuantity").value(7))
                .andExpect(jsonPath("$.content[0].nestedCount").value(2));
        mvc.perform(get("/api/inventory/containers/" + inner.getId()))
                .andExpect(jsonPath("$.location.locationId").value("DC1-RS-01-01-A-02"))
                .andExpect(jsonPath("$.path[0].lpnId").value("OUTER"))
                .andExpect(jsonPath("$.path[1].lpnId").value("MIDDLE"))
                .andExpect(jsonPath("$.container.totalQuantity").value(7));
    }

    @Test
    void showsOneContainerWithEverythingInside() throws Exception {
        mvc.perform(get("/api/inventory/containers/" + pallet.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location.locationId").value("DC1-RS-01-01-A-01"))
                .andExpect(jsonPath("$.path", hasSize(0)))
                .andExpect(jsonPath("$.container.lpnId").value("P1"))
                .andExpect(jsonPath("$.container.totalQuantity").value(5))
                .andExpect(jsonPath("$.container.lines[0].lotNumber").value("L1"))
                .andExpect(jsonPath("$.container.children[0].lpnId").value("C1"))
                .andExpect(jsonPath("$.container.children[0].lines", hasSize(2)));

        mvc.perform(get("/api/inventory/containers/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void containersPageCanListOnlyPermanentContainers() throws Exception {
        mvc.perform(get("/api/containers").param("permanent", "true"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/containers").param("permanent", "false"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].lpnId").value("C1"))
                .andExpect(jsonPath("$.content[0].parentContainer.lpnId").value("P1"));
    }

    private Item item(String code) {
        Item i = new Item(UUID.randomUUID());
        i.setItemId(code);
        i.setDescription(code);
        i.setStatus(ActiveStatus.ACTIVE);
        return items.save(i);
    }

    private Location location(String code, String zone) {
        Location l = new Location(UUID.randomUUID());
        l.setLocationId(code);
        l.setType(LocationType.STORAGE);
        l.setArea("DC1");
        l.setZone(zone);
        l.setAisle(code.substring(7, 9));
        l.setBay(code.substring(10, 12));
        l.setLevel(code.substring(13, 14));
        l.setPosition(code.substring(15, 17));
        l.setWidth(BigDecimal.TEN);
        l.setDepth(BigDecimal.TEN);
        l.setHeight(BigDecimal.TEN);
        l.setDimensionUom(DimensionUom.IN);
        return locations.save(l);
    }

    private ContainerType type(String code, ContainerCategory category) {
        ContainerType t = new ContainerType(UUID.randomUUID());
        t.setContainerTypeId(code);
        t.setDescription(code);
        t.setCategory(category);
        t.setStatus(ActiveStatus.ACTIVE);
        t.setReusable(category == ContainerCategory.PALLET);
        t.setWidth(BigDecimal.ONE);
        t.setDepth(BigDecimal.ONE);
        t.setHeight(BigDecimal.ONE);
        t.setDimensionUom(DimensionUom.IN);
        return types.save(t);
    }

    private static Container container(String lpn, ContainerType type) {
        Container c = new Container(UUID.randomUUID());
        c.setLpnId(lpn);
        c.setContainerType(type);
        c.setStatus(ContainerStatus.IN_USE);
        c.setPermanent(false);
        return c;
    }

    private void stock(Item item, int qty, String lot, String serial, Location location, Container container) {
        InventoryRecord r = new InventoryRecord(UUID.randomUUID(), item, qty);
        r.setLotNumber(lot);
        r.setSerialNumber(serial);
        if (lot != null) {
            r.setExpiryDate(LocalDate.parse("2027-04-15"));
        }
        if (location != null) {
            r.placeIn(location);
        } else {
            r.placeIn(container);
        }
        inventory.save(r);
    }
}
