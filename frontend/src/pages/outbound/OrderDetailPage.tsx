import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { RefreshCw, XCircle } from 'lucide-react';
import {
  allocateOrder,
  canAllocate,
  cancelOrder,
  getOrder,
  orderStatusLabel,
  statusClass,
  taskStatusLabel,
  ORDER_STATUSES,
  type AllocationView,
  type OrderDetail,
} from '../../api/orders';
import { inventoryStatusLabel } from '../../api/inventory';
import { errorMessage } from '../../api/http';
import { AllocationBar, formatDateTime, formatDay } from './OrdersPage';
import { PickMethodBadge, PickTaskDetailsTable, taskSummary } from '../picking/PickTaskDetailsTable';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; order: OrderDetail };

/** One order: its lines, the stock reserved for them, the pick tasks made, and what has happened. */
export function OrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const location = useLocation();
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [notice, setNotice] = useState<string | null>(
    (location.state as { created?: boolean } | null)?.created ? 'Order created and allocated' : null,
  );

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    getOrder(id, controller.signal)
      .then((order) => setState({ status: 'ready', order }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [id]);

  useEffect(() => {
    if (!notice) return;
    const handle = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(handle);
  }, [notice]);

  const run = async (action: () => Promise<OrderDetail>, success: (o: OrderDetail) => string) => {
    setBusy(true);
    setActionError(null);
    try {
      const order = await action();
      setState({ status: 'ready', order });
      setNotice(success(order));
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      setBusy(false);
      setConfirmingCancel(false);
    }
  };

  if (state.status === 'loading') return <p className="muted page">Loading…</p>;
  if (state.status === 'error') {
    return (
      <div className="page">
        <div className="notice" role="alert">
          <p>{state.message}</p>
        </div>
        <p>
          <Link to="/outbound/orders">Back to orders</Link>
        </p>
      </div>
    );
  }

  const order = state.order;
  const open = order.lines.filter((l) => l.status !== 'CANCELLED');
  const ordered = open.reduce((s, l) => s + l.orderedQuantity, 0);
  const allocated = open.reduce((s, l) => s + l.allocatedQuantity, 0);
  const allocatable = canAllocate(order.status);
  const canCancel = order.status !== 'CANCELLED';
  const openTasks = order.tasks.filter((t) => t.status === 'OPEN').length;
  const allocationsByLine = new Map<number, AllocationView[]>();
  for (const a of order.allocations) {
    allocationsByLine.set(a.lineNumber, [...(allocationsByLine.get(a.lineNumber) ?? []), a]);
  }

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/outbound/orders">Orders</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{order.orderNumber}</span>
      </nav>

      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{order.orderNumber}</span>
            <span className={`badge badge--lg badge--order-${statusClass(order.status)}`}>{orderStatusLabel(order.status)}</span>
          </h1>
          <p className="muted">{ORDER_STATUSES.find((s) => s.value === order.status)?.hint}</p>
        </div>
        <div className="button-row">
          {allocatable && (
            <button
              type="button"
              className="button"
              disabled={busy}
              onClick={() =>
                run(
                  () => allocateOrder(order.id),
                  (o) => `Allocation run: order is now ${orderStatusLabel(o.status).toLowerCase()}`,
                )
              }
            >
              <RefreshCw size={16} aria-hidden="true" />
              Allocate again
            </button>
          )}
          {canCancel && !confirmingCancel && (
            <button type="button" className="button button--danger-quiet" disabled={busy} onClick={() => setConfirmingCancel(true)}>
              <XCircle size={16} aria-hidden="true" />
              Cancel order
            </button>
          )}
        </div>
      </header>

      {confirmingCancel && (
        <div className="confirm-bar" role="group" aria-label="Confirm cancel">
          <span>
            Cancel {order.orderNumber}? Its reserved stock is released and its {openTasks} open pick task{openTasks === 1 ? '' : 's'}{' '}
            cancelled.
          </span>
          <div className="button-row">
            <button type="button" className="button" onClick={() => setConfirmingCancel(false)} disabled={busy}>
              Keep order
            </button>
            <button
              type="button"
              className="button button--danger"
              disabled={busy}
              onClick={() =>
                run(
                  () => cancelOrder(order.id),
                  () => `Order ${order.orderNumber} cancelled`,
                )
              }
            >
              Cancel order
            </button>
          </div>
        </div>
      )}

      {actionError && (
        <div className="form__error" role="alert">
          {actionError}
        </div>
      )}
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <dl className="facts">
        <div>
          <dt>Customer</dt>
          <dd>{order.customerName}</dd>
        </div>
        <div>
          <dt>Ship by</dt>
          <dd>{order.requestedShipDate ? formatDay(order.requestedShipDate) : '—'}</dd>
        </div>
        <div>
          <dt>Priority</dt>
          <dd>{order.priority}</dd>
        </div>
        <div>
          <dt>Allocated</dt>
          <dd>
            <AllocationBar allocated={allocated} ordered={ordered} />
          </dd>
        </div>
        <div>
          <dt>Pick tasks</dt>
          <dd>
            {openTasks} open
            {order.tasks.length > openTasks ? ` · ${order.tasks.length - openTasks} cancelled` : ''}
          </dd>
        </div>
        <div>
          <dt>Received</dt>
          <dd>{formatDateTime(order.createdAt)}</dd>
        </div>
      </dl>

      <section className="section" aria-labelledby="lines-title">
        <h2 id="lines-title">Lines</h2>
        <div className="table-wrap">
          <table className="table" aria-label="Order lines">
            <thead>
              <tr>
                <th scope="col" className="num">
                  #
                </th>
                <th scope="col">Item</th>
                <th scope="col">Lot</th>
                <th scope="col">Allocated</th>
                <th scope="col" className="num">
                  Allocations
                </th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {order.lines.map((l) => (
                <tr key={l.id}>
                  <td className="num">{l.lineNumber}</td>
                  <td>
                    <span className="code">{l.item.itemId}</span>
                    <span className="muted cell__sub">{l.item.description}</span>
                  </td>
                  <td className="code">{l.requestedLot ?? <span className="muted">Any</span>}</td>
                  <td>
                    <AllocationBar allocated={l.allocatedQuantity} ordered={l.orderedQuantity} />
                  </td>
                  <td className="num">{allocationsByLine.get(l.lineNumber)?.length ?? 0}</td>
                  <td>
                    <span className={`badge badge--order-${statusClass(l.status)}`}>{orderStatusLabel(l.status)}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="section" aria-labelledby="allocations-title">
        <h2 id="allocations-title">Allocations</h2>
        <p className="muted section__hint">
          Stock reserved for each line: earliest expiry first, then oldest received. A line is split across several allocations when no
          single place holds enough; each allocation becomes one pick task detail.
        </p>
        {order.allocations.length === 0 ? (
          <p className="muted">No stock is reserved for this order.</p>
        ) : (
          <div className="table-wrap">
            <table className="table" aria-label="Allocations">
              <thead>
                <tr>
                  <th scope="col" className="num">
                    Line
                  </th>
                  <th scope="col">Item</th>
                  <th scope="col" className="num">
                    Quantity
                  </th>
                  <th scope="col">Location</th>
                  <th scope="col">LPN</th>
                  <th scope="col">Lot</th>
                  <th scope="col">Serial</th>
                  <th scope="col">Expiry</th>
                  <th scope="col">Stock status</th>
                  <th scope="col">Pick task</th>
                </tr>
              </thead>
              <tbody>
                {order.lines
                  .filter((l) => allocationsByLine.has(l.lineNumber))
                  .flatMap((l) => {
                    const lineAllocations = allocationsByLine.get(l.lineNumber)!;
                    return [
                      <tr key={`line-${l.id}`} className="table__group">
                        <th scope="rowgroup" colSpan={10}>
                          Line {l.lineNumber} · <span className="code">{l.item.itemId}</span> · {l.allocatedQuantity.toLocaleString()} of{' '}
                          {l.orderedQuantity.toLocaleString()} EA from {lineAllocations.length} allocation
                          {lineAllocations.length === 1 ? '' : 's'}
                        </th>
                      </tr>,
                      ...lineAllocations.map((a) => (
                        <tr key={a.id}>
                          <td className="num">{a.lineNumber}</td>
                          <td className="code">{a.item.itemId}</td>
                          <td className="num">{a.quantity.toLocaleString()} EA</td>
                          <td>
                            {a.location ? (
                              <Link className="code" to={`/inventory/by-location?location=${a.location.id}`}>
                                {a.location.locationId}
                              </Link>
                            ) : (
                              '—'
                            )}
                          </td>
                          <td>
                            {a.container ? (
                              <Link className="code" to={`/inventory/by-container?container=${a.container.id}`}>
                                {a.container.lpnId}
                              </Link>
                            ) : (
                              <span className="muted">Loose</span>
                            )}
                          </td>
                          <td className="code">{a.lotNumber ?? '—'}</td>
                          <td className="code">{a.serialNumber ?? '—'}</td>
                          <td>{a.expiryDate ? formatDay(a.expiryDate) : '—'}</td>
                          <td>{inventoryStatusLabel(a.inventoryStatus)}</td>
                          <td>
                            {a.task ? (
                              <>
                                <Link className="code" to={`/picking/tasks/${a.task.id}`}>
                                  {a.task.taskNumber}
                                </Link>
                                <span className="muted"> · detail {a.task.detailNumber}</span>
                              </>
                            ) : (
                              <span className="muted">—</span>
                            )}
                          </td>
                        </tr>
                      )),
                    ];
                  })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="section" aria-labelledby="tasks-title">
        <h2 id="tasks-title">Pick tasks</h2>
        {order.tasks.length === 0 ? (
          <p className="muted">No pick tasks yet.</p>
        ) : (
          order.tasks.map((t) => {
            const batch = t.pickMethod === 'BATCH';
            const mine = batch ? t.details.filter((d) => d.order.id === order.id) : t.details;
            const others = t.orders.filter((o) => o.id !== order.id);
            return (
              <div key={t.id} className="task-block">
                <div className="task-block__header">
                  <Link className="code task-block__number" to={`/picking/tasks/${t.id}`}>
                    {t.taskNumber}
                  </Link>
                  <span className={`badge badge--task-${statusClass(t.status)}`}>{taskStatusLabel(t.status)}</span>
                  {batch && <PickMethodBadge method={t.pickMethod} />}
                  <span className="muted">
                    {batch
                      ? `${mine.length} of ${t.detailCount} details are for this order · batched with ${others.map((o) => o.orderNumber).join(', ')}`
                      : taskSummary(t)}
                  </span>
                  <span className="muted task-block__time">{formatDateTime(t.createdAt)}</span>
                </div>
                <PickTaskDetailsTable details={mine} label={`Details of ${t.taskNumber}`} />
              </div>
            );
          })
        )}
      </section>

      <section className="section" aria-labelledby="activity-title">
        <h2 id="activity-title">Activity</h2>
        <ol className="timeline">
          {order.events.map((e, i) => (
            <li key={i} className={`timeline__item timeline__item--${e.type.toLowerCase().replace('_', '-')}`}>
              <span className="timeline__time">{formatDateTime(e.occurredAt)}</span>
              <span className="timeline__message">{e.message}</span>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
