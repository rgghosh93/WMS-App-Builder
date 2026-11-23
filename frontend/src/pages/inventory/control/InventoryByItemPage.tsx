import { Fragment, useState } from 'react';
import { ChevronRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { listOnHand, type ItemOnHand, type OnHand } from '../../../api/control';
import { ListPage } from '../../../components/ListPage';
import { useList } from '../../../components/useList';
import { formatDay } from '../../outbound/OrdersPage';

/** How much there is of each item, and of each lot of it. */
export function InventoryByItemPage() {
  const list = useList<ItemOnHand>(listOnHand);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const toggle = (id: string) =>
    setOpen((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });

  return (
    <ListPage
      module="Inventory"
      title="Inventory by Item"
      intro="Available is what an order could get now: good, unexpired, in storage and not reserved."
      searchLabel="Search items"
      searchPlaceholder="Search item, description or lot…"
      noun={['item', 'items']}
      list={list}
      empty="No stock matches your search."
    >
      {(rows) => (
        <table className="table" aria-label="Inventory by item">
          <thead>
            <tr>
              <th scope="col">Item</th>
              {['On hand', 'Available', 'Reserved', 'Not allocatable', 'On hold', 'Damaged', 'Expired'].map((h) => (
                <th key={h} scope="col" className="num">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map(({ item, total, lots }) => (
              <Fragment key={item.id}>
                <tr>
                  <th scope="row">
                    {lots.length > 0 && (
                      <button
                        type="button"
                        className="icon-button"
                        aria-expanded={open.has(item.id)}
                        aria-label={`Lots of ${item.itemId}`}
                        onClick={() => toggle(item.id)}
                      >
                        <ChevronRight size={16} aria-hidden="true" className={open.has(item.id) ? 'turned' : undefined} />
                      </button>
                    )}{' '}
                    <Link className="code" to={`/inventory/history?q=${encodeURIComponent(item.itemId)}`} title="History">
                      {item.itemId}
                    </Link>
                    <span className="muted cell__sub">{item.description}</span>
                  </th>
                  <Numbers of={total} />
                </tr>
                {open.has(item.id) &&
                  lots.map((lot, i) => (
                    <tr key={i} className="row--sub">
                      <td>
                        {lot.lotNumber ? (
                          <>
                            Lot <span className="code">{lot.lotNumber}</span>
                          </>
                        ) : (
                          'No lot'
                        )}
                        {lot.expiryDate && <span className="muted"> · expires {formatDay(lot.expiryDate)}</span>}
                      </td>
                      <Numbers of={lot} />
                    </tr>
                  ))}
              </Fragment>
            ))}
          </tbody>
        </table>
      )}
    </ListPage>
  );
}

function Numbers({ of }: { of: OnHand }) {
  return (
    <>
      {[of.total, of.available, of.reserved, of.elsewhere, of.onHold, of.damaged, of.expired].map((n, i) => (
        <td key={i} className="num">
          {n === 0 ? <span className="muted">—</span> : i === 1 ? <strong>{n.toLocaleString()}</strong> : n.toLocaleString()}
        </td>
      ))}
    </>
  );
}
