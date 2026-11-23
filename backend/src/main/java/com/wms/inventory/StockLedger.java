package com.wms.inventory;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.wms.facility.Site;
import com.wms.inventory.StockTransaction.Type;
import com.wms.masterdata.container.Container;
import com.wms.masterdata.location.Location;
import com.wms.masterdata.owner.Owners;
import com.wms.users.AppUser;
import com.wms.users.AppUserRepository;

/** Writes the stock history. Whatever changes stock logs it here, in the same transaction. */
@Component
public class StockLedger {

    private static final int MAX_NESTING = 20;

    /** What can leave more for orders than there was. */
    private static final Set<Type> FREEING = EnumSet.of(Type.PUTAWAY, Type.MOVE, Type.REPLENISH, Type.RELEASE, Type.ADJUST, Type.COUNT);

    private final StockTransactionRepository transactions;
    private final AppUserRepository users;
    private final Owners owners;
    private final ApplicationEventPublisher publisher;

    StockLedger(StockTransactionRepository transactions, AppUserRepository users, Owners owners, ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        this.owners = owners;
        this.transactions = transactions;
        this.users = users;
    }

    /** Where stock is: the location it is at, and the LPN it is directly in. Either can be missing. */
    public record Place(String location, String lpn) {

        /** Not in the warehouse. */
        public static final Place OUTSIDE = new Place(null, null);

        public static Place of(InventoryRecord r) {
            return r.getContainer() == null ? at(r.getLocation(), null) : in(r.getContainer());
        }

        public static Place in(Container lpn) {
            return at(locationOf(lpn), lpn);
        }

        public static Place at(Location location, Container lpn) {
            return new Place(location == null ? null : location.getLocationId(), lpn == null ? null : lpn.getLpnId());
        }
    }

    /** Who did it, for what, and why. */
    public record Note(UUID userId, String userName, String reference, String reason, UUID requestId) {

        public static Note by(UUID userId, String reference) {
            return new Note(userId, null, reference, null, null);
        }

        public static Note by(UUID userId, String userName, String reference) {
            return new Note(userId, userName, reference, null, null);
        }

        public Note because(String why) {
            return new Note(userId, userName, reference, why, requestId);
        }

        public Note request(UUID id) {
            return new Note(userId, userName, reference, reason, id);
        }
    }

    public void log(Type type, InventoryRecord what, int quantity, Place from, Place to, Note note) {
        log(type, what, quantity, from, to, from.equals(Place.OUTSIDE) ? null : what.getStatus(),
                to.equals(Place.OUTSIDE) ? null : what.getStatus(), note);
    }

    /** For changes of status as well. */
    public void log(Type type, InventoryRecord what, int quantity, Place from, Place to, InventoryStatus fromStatus,
                    InventoryStatus toStatus, Note note) {
        String name = note.userName() != null ? note.userName()
                : note.userId() == null ? "System" : users.findById(note.userId()).map(AppUser::getDisplayName).orElse("System");
        transactions.save(new StockTransaction(transactions.nextNumber(), type, what, quantity, from, to, fromStatus, toStatus, note, name,
                owners.get(what.getItem().getOwnerId()).getCode()));
        if (FREEING.contains(type)) {
            publisher.publishEvent(new StockFreed(Site.current(), what.getItem().getId()));
        }
    }

    /** Where someone last handled stock, as far as is known. */
    public Optional<String> lastPlaceOf(UUID userId) {
        return transactions.findFirstByUserIdOrderByNumberDesc(userId)
                .map(t -> t.getToLocation() != null ? t.getToLocation() : t.getFromLocation());
    }

    /** Whether a device's request was already done. */
    public boolean has(UUID requestId) {
        return transactions.existsByRequestId(requestId);
    }

    /** Where a container is: its own location, or that of the outermost container it is in. */
    public static Location locationOf(Container c) {
        Container outer = c;
        for (int depth = 0; outer != null && outer.getParent() != null && depth < MAX_NESTING; depth++) {
            outer = outer.getParent();
        }
        return outer == null ? null : outer.getCurrentLocation();
    }

    public static Location locationOf(InventoryRecord r) {
        return r.getContainer() == null ? r.getLocation() : locationOf(r.getContainer());
    }
}
