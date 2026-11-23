import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { errorMessage } from '../../api/http';
import { orderStatusLabel, statusClass } from '../../api/orders';
import { getPackTask, packStatusLabel, type PackTask } from '../../api/packing';
import { formatDateTime } from '../outbound/OrdersPage';
import { CartonCard } from './CartonCard';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; task: PackTask };

/** One packing task: what came in the container, and the cartons it was packed into. */
export function PackTaskDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [state, setState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    getPackTask(id, controller.signal)
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
          <Link to="/pack-ship/packing-tasks">Back to packing tasks</Link>
        </p>
      </div>
    );
  }

  const task = state.task;
  const several = task.orders.length > 1;
  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/pack-ship/packing-tasks">Packing Tasks</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{task.taskNumber}</span>
      </nav>

      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{task.taskNumber}</span>
            <span className={`badge badge--lg badge--task-${statusClass(task.status)}`}>{packStatusLabel(task.status)}</span>
          </h1>
          <p className="muted">Packs what was picked into {task.container.lpnId} into cartons, each for one order.</p>
        </div>
      </header>

      <dl className="facts">
        <div className="facts__wide">
          <dt>{several ? `Orders (${task.orders.length})` : 'Order'}</dt>
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
        <div>
          <dt>Station</dt>
          <dd>
            <Link className="code" to={`/inventory/by-location?location=${task.station.id}`}>
              {task.station.locationId}
            </Link>
          </dd>
        </div>
        <div>
          <dt>Container</dt>
          <dd>
            <Link className="code" to={`/inventory/by-container?container=${task.container.id}`}>
              {task.container.lpnId}
            </Link>
            <span className="muted cell__sub">{task.containerType}</span>
          </dd>
        </div>
        <div>
          <dt>Picked by task</dt>
          <dd>
            <Link className="code" to={`/picking/tasks/${task.pickTask.id}`}>
              {task.pickTask.taskNumber}
            </Link>
          </dd>
        </div>
        <div>
          <dt>Packed</dt>
          <dd>
            {task.packedQuantity.toLocaleString()} of {task.totalQuantity.toLocaleString()} EA
            {task.shortQuantity > 0 && ` · ${task.shortQuantity} missing`}
          </dd>
        </div>
        <div>
          <dt>Packer</dt>
          <dd>{task.assignedTo ?? <span className="muted">Not started</span>}</dd>
        </div>
        <div>
          <dt>Dropped</dt>
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
            <dt>{task.status === 'CANCELLED' ? 'Called off' : 'Packed at'}</dt>
            <dd>{formatDateTime(task.completedAt)}</dd>
          </div>
        )}
      </dl>

      <section className="section" aria-labelledby="pack-details-title">
        <h2 id="pack-details-title">Task details</h2>
        <div className="table-wrap">
          <table className="table" aria-label="Task details">
            <thead>
              <tr>
                <th scope="col" className="num">
                  Detail
                </th>
                {several && <th scope="col">Order</th>}
                <th scope="col" className="num">
                  Line
                </th>
                <th scope="col">Item</th>
                <th scope="col">Lot / serial</th>
                <th scope="col" className="num">
                  Picked
                </th>
                <th scope="col" className="num">
                  Packed
                </th>
                <th scope="col" className="num">
                  Missing
                </th>
              </tr>
            </thead>
            <tbody>
              {task.details.map((d) => (
                <tr key={d.id}>
                  <td className="num">{d.detailNumber}</td>
                  {several && <td className="code">{d.order.orderNumber}</td>}
                  <td className="num">{d.lineNumber}</td>
                  <td>
                    <span className="code">{d.item.itemId}</span>
                    <span className="muted cell__sub">{d.item.description}</span>
                  </td>
                  <td className="code">{d.serialNumber ?? d.lotNumber ?? <span className="muted">—</span>}</td>
                  <td className="num">{d.quantity.toLocaleString()} EA</td>
                  <td className="num">
                    {d.packedQuantity.toLocaleString()} EA
                    {d.cancelledQuantity > 0 && <span className="muted cell__sub">{d.cancelledQuantity.toLocaleString()} called off</span>}
                  </td>
                  <td className="num">{d.shortQuantity > 0 ? `${d.shortQuantity} EA` : <span className="muted">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="section" aria-labelledby="pack-cartons-title">
        <h2 id="pack-cartons-title">Cartons</h2>
        {task.cartons.length === 0 ? (
          <p className="muted">No cartons yet.</p>
        ) : (
          <div className="carton-list">
            {task.cartons.map((c) => (
              <CartonCard key={c.id} carton={c} showOrder={several} linked />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
