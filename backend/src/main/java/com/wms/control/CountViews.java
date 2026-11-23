package com.wms.control;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.wms.control.ControlViews.ItemRef;
import com.wms.masterdata.item.ItemScan;

/** Read models for counting. The device never sees what is expected. */
public final class CountViews {

    private CountViews() {
    }

    public record CountSummary(UUID id, String number, CountTask.Status status, UUID locationId, String location, String plannedBy,
                               Instant plannedAt, String counter, UUID counterId, Instant countedAt, String decider, Instant decidedAt,
                               int rounds, int expected, int counted) {
        static CountSummary of(CountTask t) {
            return new CountSummary(t.getId(), t.getNumber(), t.getStatus(), t.getLocation().getId(), t.getLocation().getLocationId(),
                    t.getPlannedBy(), t.getPlannedAt(), t.getCounter(), t.getCounterId(), t.getCountedAt(), t.getDecider(),
                    t.getDecidedAt(), t.getRounds(), t.getResults().stream().mapToInt(CountResult::getExpected).sum(),
                    t.getResults().stream().mapToInt(CountResult::getCounted).sum());
        }
    }

    public record ResultView(String lpn, ItemRef item, String lotNumber, String serialNumber, int expected, int counted, int difference) {
        static ResultView of(CountResult r) {
            return new ResultView(r.getLpnId(), ItemRef.of(r.getItem()), r.getLotNumber(), r.getSerialNumber(), r.getExpected(),
                    r.getCounted(), r.difference());
        }
    }

    public record CountDetail(CountSummary task, List<ResultView> results) {
    }

    /** @param skipped locations left out because they already have a count or aren't for storage */
    public record Planned(int created, int skipped) {
    }

    // ---------- On a device ----------

    public record Waiting(String number, String location, boolean again) {
    }

    public record EntryView(UUID id, String lpn, ItemRef item, String lotNumber, String serialNumber, int quantity) {
        static EntryView of(CountEntry e) {
            return new EntryView(e.getId(), e.getContainer() == null ? null : e.getContainer().getLpnId(), ItemRef.of(e.getItem()),
                    e.getLotNumber(), e.getSerialNumber(), e.getQuantity());
        }
    }

    public record Counting(UUID id, String number, String location, List<EntryView> entries) {
    }

    /** @param counting what this person is counting, or null */
    public record CountState(Counting counting, long waitingCount, List<Waiting> waiting, String message) {
    }

    /** A scanned item, and what has to be captured with it. */
    public record CountItem(ItemRef item, ItemScan scan) {
    }
}
