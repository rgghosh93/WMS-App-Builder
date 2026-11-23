import { TRANSACTION_TYPES, listHistory, transactionLabel, type Transaction } from '../../../api/control';
import { inventoryStatusLabel } from '../../../api/inventory';
import { FilterSelect, ListPage } from '../../../components/ListPage';
import { useList } from '../../../components/useList';
import { formatDateTime, formatDay } from '../../outbound/OrdersPage';
import { Link } from 'react-router-dom';

const MOVES = { type: 'MOVE' };

/** Every change to stock, newest first. It can't be edited. */
export function StockHistoryPage() {
  const list = useList<Transaction>(listHistory);
  const get = (name: string) => list.params.get(name) ?? '';
  return (
    <ListPage
      module="Inventory"
      title="Stock History"
      intro="Every change to stock, for good: what came in and left always adds up to what is on hand."
      searchLabel="Search stock history"
      searchPlaceholder="Search item, lot, LPN, location, reference or person…"
      filters={
        <>
          <FilterSelect label="Change" value={get('type')} options={TRANSACTION_TYPES} onChange={(type) => list.update({ type })} />
          <label className="select-inline">
            <span>From</span>
            <input type="date" value={get('from')} onChange={(e) => list.update({ from: e.target.value })} />
          </label>
          <label className="select-inline">
            <span>To</span>
            <input type="date" value={get('to')} onChange={(e) => list.update({ to: e.target.value })} />
          </label>
        </>
      }
      noun={['change', 'changes']}
      list={list}
      empty="Nothing matches your search."
    >
      {(rows) => <TransactionTable rows={rows} />}
    </ListPage>
  );
}

/** Stock moved between storage locations. */
export function InventoryMovesPage() {
  const list = useList<Transaction>(listHistory, MOVES);
  return (
    <ListPage
      module="Inventory"
      title="Inventory Moves"
      intro={
        <>
          Stock moved from one storage location to another. It is moved on a mobile device under <Link to="/rf/move">Move</Link>.
        </>
      }
      searchLabel="Search moves"
      searchPlaceholder="Search item, lot, LPN, location or person…"
      noun={['move', 'moves']}
      list={list}
      empty="No moves yet."
    >
      {(rows) => <TransactionTable rows={rows} />}
    </ListPage>
  );
}

function TransactionTable({ rows }: { rows: Transaction[] }) {
  return (
    <table className="table" aria-label="Stock history">
      <thead>
        <tr>
          <th scope="col">When</th>
          <th scope="col">Change</th>
          <th scope="col">Item</th>
          <th scope="col" className="num">
            Quantity
          </th>
          <th scope="col">From</th>
          <th scope="col">To</th>
          <th scope="col">For</th>
          <th scope="col">Who</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((t) => (
          <tr key={t.id}>
            <td className="muted">{formatDateTime(t.occurredAt)}</td>
            <td>
              {transactionLabel(t.type)}
              {t.fromStatus && t.toStatus && t.fromStatus !== t.toStatus && (
                <span className="muted cell__sub">
                  {inventoryStatusLabel(t.fromStatus)} → {inventoryStatusLabel(t.toStatus)}
                </span>
              )}
            </td>
            <td>
              <span className="code">{t.itemCode}</span>
              {(t.lotNumber || t.serialNumber || t.expiryDate) && (
                <span className="muted cell__sub">
                  {[t.lotNumber && `lot ${t.lotNumber}`, t.serialNumber, t.expiryDate && `expires ${formatDay(t.expiryDate)}`].filter(Boolean).join(' · ')}
                </span>
              )}
            </td>
            <td className="num">{t.quantity.toLocaleString()} EA</td>
            <td>
              <Place location={t.fromLocation} lpn={t.fromLpn} />
            </td>
            <td>
              <Place location={t.toLocation} lpn={t.toLpn} />
            </td>
            <td className="cell--wrap">
              {t.reference && <span className="code">{t.reference}</span>}
              {t.reason && <span className="muted cell__sub">{t.reason}</span>}
            </td>
            <td>{t.userName}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Place({ location, lpn }: { location: string | null; lpn: string | null }) {
  if (!location && !lpn) return <span className="muted">—</span>;
  return (
    <>
      <span className="code">{location ?? lpn}</span>
      {location && lpn && <span className="muted cell__sub">in {lpn}</span>}
      {!location && <span className="muted cell__sub">carried</span>}
    </>
  );
}
