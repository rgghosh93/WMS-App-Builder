import { useCallback, useEffect, useState } from 'react';
import { Pencil, Plus } from 'lucide-react';
import { ADJUSTMENT_STATUSES, decideAdjustment, getInventorySettings, listAdjustments, type Adjustment } from '../../../api/control';
import { errorMessage } from '../../../api/http';
import { FilterSelect, ListPage } from '../../../components/ListPage';
import { useList } from '../../../components/useList';
import { Can, useCan, useSession } from '../../../session/SessionContext';
import { formatDateTime } from '../../outbound/OrdersPage';
import { AddStockForm } from './AddStockForm';
import { ChangeQuantityForm } from './ChangeQuantityForm';

/** Corrections of how much stock there is, made and waiting for approval. */
export function AdjustmentsPage() {
  const list = useList<Adjustment>(listAdjustments);
  const [limit, setLimit] = useState<number | null>(null);
  const [form, setForm] = useState<'change' | 'add' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mayApprove = useCan('INVENTORY_APPROVE');
  const me = useSession()?.user.id;

  useEffect(() => {
    const controller = new AbortController();
    getInventorySettings(controller.signal)
      .then((s) => setLimit(s.approvalLimit))
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  const done = (a: Adjustment) => {
    setForm(null);
    setNotice(a.status === 'PENDING' ? `${a.number} is waiting for someone else to approve it` : `${a.number} made`);
    list.reload();
  };

  const decide = async (a: Adjustment, approve: boolean) => {
    setBusy(true);
    setError(null);
    try {
      const decided = await decideAdjustment(a.id, approve);
      setNotice(`${decided.number} ${approve ? 'approved' : 'rejected'}`);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      list.reload();
    }
  };

  return (
    <ListPage
      module="Inventory"
      title="Adjustments"
      intro={limit === null ? 'Corrections of how much stock there is.' : `Corrections of how much stock there is. More than ${limit.toLocaleString()} EA at once needs a second person.`}
      actions={
        <Can permission="INVENTORY">
          <button type="button" className="button" onClick={() => setForm('add')}>
            <Plus size={16} aria-hidden="true" />
            Add found stock
          </button>
          <button type="button" className="button button--primary" onClick={() => setForm('change')}>
            <Pencil size={16} aria-hidden="true" />
            Correct a quantity
          </button>
        </Can>
      }
      searchLabel="Search adjustments"
      searchPlaceholder="Search number, item, place, reason or person…"
      filters={<FilterSelect label="Status" value={list.params.get('status') ?? ''} options={ADJUSTMENT_STATUSES} onChange={(status) => list.update({ status })} />}
      noun={['adjustment', 'adjustments']}
      list={list}
      notice={notice}
      onNoticeGone={useCallback(() => setNotice(null), [])}
      empty={list.params.toString() ? 'No adjustments match your search.' : 'No adjustments yet.'}
      before={
        <>
          {error && (
            <div className="form__error" role="alert">
              {error}
            </div>
          )}
          {form === 'change' && <ChangeQuantityForm limit={limit} onClose={() => setForm(null)} onDone={done} />}
          {form === 'add' && <AddStockForm limit={limit} onClose={() => setForm(null)} onDone={done} />}
        </>
      }
    >
      {(rows) => (
        <table className="table" aria-label="Adjustments">
          <thead>
            <tr>
              <th scope="col">Number</th>
              <th scope="col">Item</th>
              <th scope="col">Where</th>
              <th scope="col" className="num">
                Change
              </th>
              <th scope="col">Reason</th>
              <th scope="col">Asked by</th>
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((a) => (
              <tr key={a.id}>
                <th scope="row" className="code">
                  {a.number}
                </th>
                <td>
                  <span className="code">{a.item.itemId}</span>
                  {(a.lotNumber || a.serialNumber) && <span className="muted cell__sub">{[a.lotNumber && `lot ${a.lotNumber}`, a.serialNumber].filter(Boolean).join(' · ')}</span>}
                </td>
                <td className="code">{a.place}</td>
                <td className="num">
                  {a.change > 0 ? '+' : '−'}
                  {Math.abs(a.change).toLocaleString()} EA
                </td>
                <td className="cell--wrap">{a.reason}</td>
                <td>
                  {a.requester}
                  <span className="muted cell__sub">{formatDateTime(a.requestedAt)}</span>
                </td>
                <td>
                  <span className={`badge badge--adj-${a.status.toLowerCase()}`}>{ADJUSTMENT_STATUSES.find((s) => s.value === a.status)?.label}</span>
                  {a.decider && <span className="muted cell__sub">by {a.decider}</span>}
                  {a.status === 'PENDING' && mayApprove && (
                    <span className="button-row">
                      {a.requestedBy !== me && (
                        <button type="button" className="link-button" disabled={busy} aria-label={`Approve ${a.number}`} onClick={() => void decide(a, true)}>
                          Approve
                        </button>
                      )}
                      <button type="button" className="link-button" disabled={busy} aria-label={`Reject ${a.number}`} onClick={() => void decide(a, false)}>
                        Reject
                      </button>
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ListPage>
  );
}
