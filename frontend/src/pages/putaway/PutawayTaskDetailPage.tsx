import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { errorMessage } from '../../api/http';
import { inventoryStatusLabel } from '../../api/inventory';
import { statusClass } from '../../api/orders';
import { getPutawayTask, overrideReasonLabel, putawayStatusLabel, type PutawayTask } from '../../api/putaway';
import { formatDateTime, formatDay } from '../outbound/OrdersPage';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; task: PutawayTask };

/** One put-away task: the LPN, where it was sent and why, and where it went. */
export function PutawayTaskDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [state, setState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    getPutawayTask(id, controller.signal)
      .then((task) => setState({ status: 'ready', task }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [id]);

  if (state.status === 'loading') return <p className="muted page">Loading…</p>;
  if (state.status === 'error') {
    return (
      <div className="page">
        <div className="notice" role="alert">
          <p>{state.message}</p>
        </div>
        <p>
          <Link to="/putaway/tasks">Back to put-away tasks</Link>
        </p>
      </div>
    );
  }

  const task = state.task;
  const location = (l: { id: string; locationId: string }) => (
    <Link className="code" to={`/inventory/by-location?location=${l.id}`}>
      {l.locationId}
    </Link>
  );

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/putaway/tasks">Put-away Tasks</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{task.taskNumber}</span>
      </nav>

      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{task.taskNumber}</span>
            <span className={`badge badge--lg badge--task-${statusClass(task.status)}`}>{putawayStatusLabel(task.status)}</span>
          </h1>
          <p className="muted">Moves {task.lpn.lpnId} from receiving into storage.</p>
        </div>
      </header>

      <dl className="facts">
        <div>
          <dt>LPN</dt>
          <dd>
            <Link className="code" to={`/inventory/by-container?container=${task.lpn.id}`}>
              {task.lpn.lpnId}
            </Link>
            <span className="muted cell__sub">{task.lpn.containerType}</span>
          </dd>
        </div>
        <div>
          <dt>From</dt>
          <dd>{location(task.fromLocation)}</dd>
        </div>
        <div>
          <dt>Directed to</dt>
          <dd>
            {task.directedLocation ? (
              location(task.directedLocation)
            ) : (
              <span className="muted">{task.status === 'OPEN' ? 'Decided when started' : 'No rule found room'}</span>
            )}
            {task.ruleName && <span className="muted cell__sub">{task.ruleName}</span>}
          </dd>
        </div>
        {task.toLocation && (
          <div>
            <dt>Put in</dt>
            <dd>
              {location(task.toLocation)}
              <span className="muted cell__sub">
                {task.handling === 'EMPTY_LPN' ? 'emptied into it' : 'in its LPN'}
                {task.overrideReason && ` · instead: ${overrideReasonLabel(task.overrideReason).toLowerCase()}`}
              </span>
            </dd>
          </div>
        )}
        <div>
          <dt>Who</dt>
          <dd>{task.assignedTo ?? <span className="muted">Not started</span>}</dd>
        </div>
        <div>
          <dt>Received</dt>
          <dd>{formatDateTime(task.createdAt)}</dd>
        </div>
        {task.startedAt && (
          <div>
            <dt>Started</dt>
            <dd>{formatDateTime(task.startedAt)}</dd>
          </div>
        )}
        {task.completedAt && (
          <div>
            <dt>{task.status === 'CANCELLED' ? 'Cancelled' : 'Put away'}</dt>
            <dd>{formatDateTime(task.completedAt)}</dd>
          </div>
        )}
      </dl>

      <section className="section" aria-labelledby="putaway-contents">
        <h2 id="putaway-contents">Contents</h2>
        {task.lines.length === 0 ? (
          <p className="muted">Nothing in it.</p>
        ) : (
          <div className="table-wrap">
            <table className="table" aria-label="Contents">
              <thead>
                <tr>
                  <th scope="col">Item</th>
                  <th scope="col">Lot / expiry</th>
                  <th scope="col">Status</th>
                  <th scope="col" className="num">
                    Quantity
                  </th>
                </tr>
              </thead>
              <tbody>
                {task.lines.map((l, i) => (
                  <tr key={i}>
                    <td>
                      <span className="code">{l.item.itemId}</span>
                      <span className="muted cell__sub">{l.item.description}</span>
                    </td>
                    <td>
                      <span className="code">{l.lotNumber ?? '—'}</span>
                      {l.expiryDate && <span className="muted cell__sub">expires {formatDay(l.expiryDate)}</span>}
                    </td>
                    <td>
                      <span className={`badge badge--inv-${statusClass(l.status)}`}>{inventoryStatusLabel(l.status)}</span>
                    </td>
                    <td className="num">{l.quantity.toLocaleString()} EA</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {task.skips.length > 0 && (
        <section className="section" aria-labelledby="putaway-skips">
          <h2 id="putaway-skips">Locations passed over</h2>
          <ol className="timeline">
            {task.skips.map((s, i) => (
              <li key={i} className="timeline__item timeline__item--short">
                <span className="timeline__time">{formatDateTime(s.skippedAt)}</span>
                <span className="timeline__message">
                  <span className="code">{s.location.locationId}</span>: {overrideReasonLabel(s.reason).toLowerCase()} ({s.skippedBy})
                </span>
              </li>
            ))}
          </ol>
        </section>
      )}
    </div>
  );
}
