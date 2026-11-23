import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getPickTask, orderStatusLabel, statusClass, taskStatusLabel, type PickTaskView } from '../../api/orders';
import { errorMessage } from '../../api/http';
import { formatDateTime } from '../outbound/OrdersPage';
import { PickMethodBadge, PickTaskDetailsTable } from './PickTaskDetailsTable';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; task: PickTaskView };

/** One pick task: its order and every detail (one per allocation) the picker works through. */
export function PickTaskDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [state, setState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    getPickTask(id, controller.signal)
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
          <Link to="/picking/tasks">Back to pick tasks</Link>
        </p>
      </div>
    );
  }

  const task = state.task;
  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/picking/tasks">Pick Tasks</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{task.taskNumber}</span>
      </nav>

      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{task.taskNumber}</span>
            <span className={`badge badge--lg badge--task-${statusClass(task.status)}`}>{taskStatusLabel(task.status)}</span>
            <PickMethodBadge method={task.pickMethod} />
          </h1>
          <p className="muted">
            {task.pickMethod === 'BATCH'
              ? `Batch pick for ${task.orders.length} orders in one walk. Each detail picks one allocation; the order shown is who it is for, so the container can be sorted at packing.`
              : 'Each detail picks the stock of one allocation.'}
          </p>
        </div>
      </header>

      <dl className="facts">
        <div className="facts__wide">
          <dt>{task.orders.length === 1 ? 'Order' : `Orders (${task.orders.length})`}</dt>
          <dd>
            <ul className="order-list">
              {task.orders.map((o) => (
                <li key={o.id}>
                  <Link className="code" to={`/outbound/orders/${o.id}`}>
                    {o.orderNumber}
                  </Link>{' '}
                  <span className="muted">{o.customerName}</span>{' '}
                  <span className={`badge badge--sm badge--order-${statusClass(o.status)}`}>{orderStatusLabel(o.status)}</span>
                </li>
              ))}
            </ul>
          </dd>
        </div>
        {task.pickZone && (
          <div>
            <dt>Pick zone</dt>
            <dd className="code">{task.pickZone}</dd>
          </div>
        )}
        <div>
          <dt>Details</dt>
          <dd>{task.detailCount}</dd>
        </div>
        <div>
          <dt>Lines</dt>
          <dd>{task.lineCount}</dd>
        </div>
        <div>
          <dt>Locations</dt>
          <dd>{task.locationCount}</dd>
        </div>
        <div>
          <dt>Picked</dt>
          <dd>
            {task.pickedQuantity.toLocaleString()} of {task.totalQuantity.toLocaleString()} EA
          </dd>
        </div>
        <div>
          <dt>Picker</dt>
          <dd>{task.assignedTo ?? <span className="muted">Not started</span>}</dd>
        </div>
        <div>
          <dt>Picked into</dt>
          <dd>
            {task.toContainer ? (
              <Link className="code" to={`/inventory/by-container?container=${task.toContainer.id}`}>
                {task.toContainer.lpnId}
              </Link>
            ) : (
              <span className="muted">—</span>
            )}
          </dd>
        </div>
        <div>
          <dt>Dropped at</dt>
          <dd>
            {task.dropLocation ? (
              <Link className="code" to={`/inventory/by-location?location=${task.dropLocation.id}`}>
                {task.dropLocation.locationId}
              </Link>
            ) : (
              <span className="muted">—</span>
            )}
          </dd>
        </div>
        <div>
          <dt>Created</dt>
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
            <dt>Completed</dt>
            <dd>{formatDateTime(task.completedAt)}</dd>
          </div>
        )}
      </dl>

      <section className="section" aria-labelledby="details-title">
        <h2 id="details-title">Task details</h2>
        <PickTaskDetailsTable details={task.details} label="Task details" showOrder={task.pickMethod === 'BATCH'} />
      </section>
    </div>
  );
}
