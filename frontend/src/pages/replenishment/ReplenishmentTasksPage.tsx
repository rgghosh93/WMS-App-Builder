import { useCallback, useState } from 'react';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { errorMessage } from '../../api/http';
import { statusClass } from '../../api/orders';
import {
  REPLENISHMENT_STATUSES,
  cancelReplenishment,
  listReplenishmentTasks,
  reasonLabel,
  replenishmentStatusLabel,
  runReplenishment,
  type ReplenishmentTask,
  type RunResult,
} from '../../api/replenishment';
import { FilterSelect, ListPage } from '../../components/ListPage';
import { useList } from '../../components/useList';
import { Can, useCan } from '../../session/SessionContext';
import { formatDateTime, formatDay } from '../outbound/OrdersPage';

/** Stock being brought to pick locations, newest first. */
export function ReplenishmentTasksPage() {
  const list = useList<ReplenishmentTask>(listReplenishmentTasks);
  const may = useCan('REPLENISHMENT');
  const [notice, setNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** The task to be cancelled, waiting for a yes. */
  const [asking, setAsking] = useState<string | null>(null);

  const act = async (action: () => Promise<string>) => {
    setBusy(true);
    setProblem(null);
    try {
      setNotice(await action());
      setAsking(null);
    } catch (error) {
      setProblem(errorMessage(error));
    } finally {
      setBusy(false);
      list.reload();
    }
  };

  return (
    <ListPage
      module="Replenishment"
      title="Replenishment Tasks"
      intro={
        <>
          Made by the <Link to="/replenishment/rules">rules</Link> about once a minute, and done on a mobile device under <Link to="/rf/replenish">Replenish</Link>. Until
          someone starts a task, an order that needs its stock takes it back.
        </>
      }
      actions={
        <Can permission="REPLENISHMENT">
          <button type="button" className="button button--primary" disabled={busy} onClick={() => void act(async () => ran(await runReplenishment()))}>
            <RefreshCw size={16} aria-hidden="true" />
            Run now
          </button>
        </Can>
      }
      searchLabel="Search replenishment tasks"
      searchPlaceholder="Search task, item, location, LPN, lot or person…"
      filters={<FilterSelect label="Status" value={list.params.get('status') ?? ''} options={REPLENISHMENT_STATUSES} onChange={(status) => list.update({ status })} />}
      noun={['task', 'tasks']}
      list={list}
      notice={notice}
      onNoticeGone={useCallback(() => setNotice(null), [])}
      empty={list.params.toString() ? 'No tasks match your search.' : 'No replenishment yet.'}
      before={
        problem && (
          <div className="notice" role="alert">
            <p>{problem}</p>
          </div>
        )
      }
    >
      {(rows) => (
        <table className="table" aria-label="Replenishment tasks">
          <thead>
            <tr>
              <th scope="col">Task</th>
              <th scope="col">Why</th>
              <th scope="col">Item</th>
              <th scope="col">From</th>
              <th scope="col">To</th>
              <th scope="col" className="num">
                Quantity
              </th>
              <th scope="col">Status</th>
              <th scope="col">Who</th>
              <th scope="col">Created</th>
              {may && (
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.id}>
                <th scope="row" className="code">
                  {t.taskNumber}
                </th>
                <td>{reasonLabel(t.reason)}</td>
                <td>
                  <span className="code">{t.item.itemId}</span>
                  <span className="muted cell__sub">{detail(t) || t.item.description}</span>
                </td>
                <td>
                  <span className="code">{t.fromLocation.locationId}</span>
                  {t.fromLpn && <span className="muted cell__sub">{t.fromLpn}</span>}
                </td>
                <td className="code">{t.toLocation.locationId}</td>
                <td className="num">
                  {t.quantity.toLocaleString()} EA
                  {t.movedQuantity > 0 && t.movedQuantity < t.quantity && <span className="muted cell__sub">{t.movedQuantity.toLocaleString()} moved</span>}
                  {t.shortQuantity > 0 && <span className="muted cell__sub">{t.shortQuantity.toLocaleString()} missing</span>}
                </td>
                <td className={t.note ? 'cell--wrap' : undefined}>
                  <span className={`badge badge--task-${statusClass(t.status)}`} title={t.note ?? undefined}>
                    {replenishmentStatusLabel(t.status)}
                  </span>
                  {t.note && <span className="muted cell__sub">{t.note}</span>}
                </td>
                <td>{t.assignedTo ?? <span className="muted">—</span>}</td>
                <td className="muted">{formatDateTime(t.createdAt)}</td>
                {may && (
                  <td>
                    {(t.status === 'OPEN' || t.status === 'IN_PROGRESS') &&
                      (asking === t.id ? (
                        <span className="button-row" role="group" aria-label={`Cancel ${t.taskNumber}`}>
                          <button
                            type="button"
                            className="button button--danger"
                            disabled={busy}
                            onClick={() => void act(async () => `Cancelled ${(await cancelReplenishment(t.id)).taskNumber}`)}
                          >
                            Cancel it
                          </button>
                          <button type="button" className="button" disabled={busy} onClick={() => setAsking(null)}>
                            Keep it
                          </button>
                        </span>
                      ) : (
                        <button type="button" className="link-button" aria-label={`Cancel ${t.taskNumber}`} onClick={() => setAsking(t.id)}>
                          Cancel
                        </button>
                      ))}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ListPage>
  );
}

function detail(t: ReplenishmentTask): string {
  return [t.lotNumber && `lot ${t.lotNumber}`, t.expiryDate && `expires ${formatDay(t.expiryDate)}`].filter(Boolean).join(' · ');
}

/** What a run did. */
export function ran({ rules, quantity, still }: RunResult): string {
  if (rules === 0) return 'There are no active rules.';
  const made = quantity > 0 ? `Planned ${quantity.toLocaleString()} EA.` : 'Nothing more to plan.';
  return still > 0 ? `${made} ${still} rule${still === 1 ? ' is' : 's are'} below the minimum, with nothing more to bring or no room for it.` : made;
}
