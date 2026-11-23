package com.wms.putaway;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.wms.inventory.InventoryRecord;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.item.Item;
import com.wms.masterdata.location.Location;
import com.wms.putaway.Occupancy.Problem;

/** Whether there is room in a location, for whatever else puts things in storage (moves). */
@Component
public class StorageSpace {

    private final PutawayDirector director;
    private final PutawayControlRepository control;

    StorageSpace(PutawayDirector director, PutawayControlRepository control) {
        this.director = director;
        this.control = control;
    }

    /**
     * @param refused why it can never go there, or null
     * @param over    why there is no room by the books, or null
     */
    public record Check(String refused, String over) {
    }

    /** Only one transaction at a time fills locations, put-away included. */
    public void lock() {
        control.lock();
    }

    public Check forLpn(Container lpn, Location to) {
        return check(director.load(lpn), PutawayHandling.KEEP_LPN, to);
    }

    /** @param stock loose stock, of the quantity to put there */
    public Check forLoose(InventoryRecord stock, Location to) {
        return check(new Load(null, List.of(), List.of(stock)), PutawayHandling.EMPTY_LPN, to);
    }

    /** How many more eaches of an item a location can bear, loose. The other limits can be passed over, but not this one. */
    public int mostByWeight(Item item, Location to) {
        return director.occupancy(List.of(to), null).get(to.getId()).mostByWeight(to, item);
    }

    public List<InventoryRecord> contents(Container lpn) {
        return director.load(lpn).stock();
    }

    private Check check(Load load, PutawayHandling handling, Location to) {
        List<Problem> problems = director.occupancy(List.of(to), null).get(to.getId()).problems(to, load, handling);
        String hard = problems.stream().filter(Problem::hard).map(Problem::message).findFirst().orElse(null);
        String soft = problems.stream().filter(p -> !p.hard()).map(Problem::message).collect(Collectors.joining(". "));
        return new Check(hard, soft.isEmpty() ? null : soft);
    }
}
