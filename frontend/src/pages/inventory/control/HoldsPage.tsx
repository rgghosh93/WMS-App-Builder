import { useCallback, useState } from 'react';
import { Lock, Unlock } from 'lucide-react';
import { listStock, type HoldResult, type Stock } from '../../../api/control';
import { inventoryStatusLabel } from '../../../api/inventory';
import { statusClass } from '../../../api/orders';
import { ListPage } from '../../../components/ListPage';
import { useList } from '../../../components/useList';
import { Can, useCan } from '../../../session/SessionContext';
import { formatDateTime, formatDay } from '../../outbound/OrdersPage';
import { HoldForm } from './HoldForm';
import { ReleaseForm } from './ReleaseForm';

const HELD = { held: 'true' };

/** Stock that is on hold or damaged, and putting more on hold. */
export function HoldsPage() {
  const list = useList<Stock>(listStock, HELD);
  const [selected, setSelected] = useState<Map<string, Stock>>(new Map());
  const [form, setForm] = useState<'hold' | 'release' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const may = useCan('INVENTORY');

  const toggle = (s: Stock) =>
    setSelected((current) => {
      const next = new Map(current);
      if (!next.delete(s.id)) next.set(s.id, s);
      return next;
    });

  const done = (message: string) => {
    setForm(null);
    setSelected(new Map());
    setNotice(message);
    list.reload();
  };

  return (
    <ListPage
      module="Inventory"
      title="Holds"
      intro="Stock on hold or damaged isn’t allocated, picked, packed or shipped until it is released."
      actions={
        <Can permission="INVENTORY">
          <button type="button" className="button" disabled={selected.size === 0} onClick={() => setForm('release')}>
            <Unlock size={16} aria-hidden="true" />
            Release{selected.size > 0 ? ` ${selected.size}` : ''}
          </button>
          <button type="button" className="button button--primary" onClick={() => setForm('hold')}>
            <Lock size={16} aria-hidden="true" />
            Put on hold
          </button>
        </Can>
      }
      searchLabel="Search held stock"
      searchPlaceholder="Search item, lot, LPN, location or reason…"
      noun={['record', 'records']}
      list={list}
      notice={notice}
      onNoticeGone={useCallback(() => setNotice(null), [])}
      empty={list.params.get('q') ? 'No held stock matches your search.' : 'Nothing is on hold.'}
      before={
        <>
          {form === 'hold' && <HoldForm onClose={() => setForm(null)} onDone={(r) => done(holdMessage(r))} />}
          {form === 'release' && (
            <ReleaseForm stock={[...selected.values()]} onClose={() => setForm(null)} onDone={(r) => done(`Released ${r.quantity.toLocaleString()} EA`)} />
          )}
        </>
      }
    >
      {(rows) => (
        <table className="table" aria-label="Held stock">
          <thead>
            <tr>
              {may && (
                <th scope="col" className="table__select">
                  <span className="visually-hidden">Select to release</span>
                </th>
              )}
              <th scope="col">Item</th>
              <th scope="col">Where</th>
              <th scope="col" className="num">
                Quantity
              </th>
              <th scope="col">Status</th>
              <th scope="col">Reason</th>
              <th scope="col">Since</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((s) => (
              <tr key={s.id}>
                {may && (
                  <td className="table__select">
                    <input type="checkbox" aria-label={`Select ${s.item.itemId} at ${s.location ?? s.lpn}`} checked={selected.has(s.id)} onChange={() => toggle(s)} />
                  </td>
                )}
                <td>
                  <span className="code">{s.item.itemId}</span>
                  <span className="muted cell__sub">
                    {[s.lotNumber && `lot ${s.lotNumber}`, s.serialNumber, s.expiryDate && `expires ${formatDay(s.expiryDate)}`].filter(Boolean).join(' · ') || s.item.description}
                  </span>
                </td>
                <td>
                  <span className="code">{s.location ?? 'Carried'}</span>
                  {s.lpn && <span className="muted cell__sub">in {s.lpn}</span>}
                </td>
                <td className="num">
                  {s.quantity.toLocaleString()} EA
                  {s.reserved > 0 && <span className="muted cell__sub">{s.reserved.toLocaleString()} for orders</span>}
                </td>
                <td>
                  <span className={`badge badge--inv-${statusClass(s.status)}`}>{inventoryStatusLabel(s.status)}</span>
                </td>
                <td className="cell--wrap">{s.holdReason ?? <span className="muted">Not recorded</span>}</td>
                <td className="muted">{s.heldAt ? formatDateTime(s.heldAt) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ListPage>
  );
}

function holdMessage(r: HoldResult): string {
  return [
    `Held ${r.quantity.toLocaleString()} EA`,
    r.ordersReallocated > 0 && `${r.ordersReallocated} order${r.ordersReallocated === 1 ? '' : 's'} looked for other stock`,
    r.stuck > 0 && `${r.stuck.toLocaleString()} EA already being picked or picked stay with their orders`,
  ]
    .filter(Boolean)
    .join('. ');
}
