package com.wms.work;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wms.inventory.StockLedger;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.location.LocationRepository;
import com.wms.users.DeviceUser;
import com.wms.work.WorkViews.Next;
import com.wms.work.WorkViews.TaskRow;

/**
 * What someone should do next, of all they may do: what they were told to do, then the most urgent, and of
 * equally urgent tasks the one nearest to where they last handled stock, whatever kind it is.
 */
@Service
@Transactional
public class NextService {

    private final WorkService work;
    private final Directions said;
    private final TaskDirectionRepository directions;
    private final WorkControlRepository control;
    private final StockLedger ledger;
    private final LocationRepository locations;

    NextService(WorkService work, Directions said, TaskDirectionRepository directions, WorkControlRepository control, StockLedger ledger,
                LocationRepository locations) {
        this.work = work;
        this.said = said;
        this.directions = directions;
        this.control = control;
        this.ledger = ledger;
        this.locations = locations;
    }

    /** @param skip tasks they don't want now */
    public Next next(DeviceUser user, List<UUID> skip) {
        control.lock(); // so two people aren't offered the same task
        List<WorkItem> items = work.items(null);
        directions.findByClaimedBy(user.id()).forEach(TaskDirection::unclaim);
        List<TaskRow> doing = work.rows(items.stream().filter(i -> user.id().equals(i.userId())).toList());
        Set<WorkKind> offered = work.offered();
        Map<UUID, TaskDirection> of = said.of(items.stream().map(WorkItem::id).toList());
        List<WorkItem> possible = items.stream().filter(i -> !i.started() && i.ready() && offered.contains(i.kind())
                && user.permissions().contains(i.kind().permission())
                && (of.get(i.id()) == null || !of.get(i.id()).isForSomeoneElse(user.id()))).toList();
        if (!doing.isEmpty() || possible.isEmpty()) {
            return new Next(doing, null, possible.size());
        }
        Set<UUID> skipped = skip == null ? Set.of() : new HashSet<>(skip);
        Location here = ledger.lastPlaceOf(user.id()).flatMap(locations::findByLocationId).orElse(null);
        Map<UUID, WorkItem> byId = possible.stream().collect(Collectors.toMap(WorkItem::id, Function.identity()));
        TaskRow offer = work.rows(possible).stream().filter(r -> !skipped.contains(r.id()))
                .min(Comparator.comparing((TaskRow r) -> r.assignedTo() == null).thenComparingInt(TaskRow::priority)
                        .thenComparingInt(r -> distance(here, byId.get(r.id()).at())).thenComparing(WorkService.URGENCY))
                .orElse(null);
        if (offer != null) {
            TaskDirection d = of.get(offer.id());
            if (d == null) {
                d = directions.save(new TaskDirection(offer.id(), offer.kind()));
            }
            d.claim(user.id());
            directions.flush();
        }
        return new Next(List.of(), offer, possible.size());
    }

    /** Roughly how far: the same location, aisle, zone and area, or further. */
    private static int distance(Location from, Location to) {
        if (from == null || to == null) {
            return 5;
        }
        if (from.getId().equals(to.getId())) {
            return 0;
        }
        if (!Objects.equals(from.getArea(), to.getArea())) {
            return 4;
        }
        if (!Objects.equals(from.getZone(), to.getZone())) {
            return 3;
        }
        return Objects.equals(from.getAisle(), to.getAisle()) ? 1 : 2;
    }
}
