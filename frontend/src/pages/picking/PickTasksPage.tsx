import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  TASK_STATUSES,
  listPickTasks,
  statusClass,
  taskStatusLabel,
  type PickMethod,
  type PickTaskQuery,
  type PickTaskStatus,
  type PickTaskView,
} from '../../api/orders';
import { PickMethodBadge } from './PickTaskDetailsTable';
import { errorMessage, type Page } from '../../api/http';
import { listZones, type Zone } from '../../api/zones';
import { FilterSelect } from '../../components/ListPage';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { formatDateTime } from '../outbound/OrdersPage';

type LoadState =
  | { status: 'loading'; previous?: Page<PickTaskView> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<PickTaskView> };

/** Every pick task created by allocation, newest first. */
export function PickTasksPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [zones, setZones] = useState<Zone[]>([]);

  // Only for the filter, so the page does without if they can't be had.
  useEffect(() => {
    const controller = new AbortController();
    listZones('PICKING', controller.signal).then(setZones, () => undefined);
    return () => controller.abort();
  }, []);

  const updateQuery = useCallback(
    (changes: Partial<PickTaskQuery>) => {
      setParams((current) => writeQuery({ ...readQuery(current), page: 0, ...changes }), { replace: true });
    },
    [setParams],
  );

  useEffect(() => {
    const handle = setTimeout(() => {
      if (query.q !== searchText.trim()) updateQuery({ q: searchText.trim() });
    }, 250);
    return () => clearTimeout(handle);
  }, [searchText, query.q, updateQuery]);

  const queryKey = params.toString();
  useEffect(() => {
    const controller = new AbortController();
    setState((current) => ({
      status: 'loading',
      previous: current.status === 'ready' ? current.page : current.status === 'loading' ? current.previous : undefined,
    }));
    listPickTasks(readQuery(new URLSearchParams(queryKey)), controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [queryKey, reloadKey]);

  const page = state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined;
  const zoned = zones.length > 0 || Boolean(page?.content.some((t) => t.pickZone));

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Picking</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Pick Tasks</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Pick Tasks</h1>
          <p className="muted">Each allocation run makes one task, or one for each pick zone: discrete for one order, batch for several orders picked in one walk. Open a task to see its details, one pick per allocation.</p>
        </div>
      </header>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={searchText} onChange={setSearchText} placeholder="Search task, order, item, location, container or picker…" label="Search pick tasks" />
        </div>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as PickTaskStatus | '' })}>
            <option value="">All</option>
            {TASK_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <label className="select-inline">
          <span>Method</span>
          <select value={query.pickMethod} onChange={(e) => updateQuery({ pickMethod: e.target.value as PickMethod | '' })}>
            <option value="">All</option>
            <option value="DISCRETE">Discrete</option>
            <option value="BATCH">Batch</option>
          </select>
        </label>
        {(zones.length > 0 || query.pickZone) && (
          <FilterSelect
            label="Pick zone"
            value={query.pickZone}
            options={[...new Set([...zones.map((z) => z.code), query.pickZone])].filter(Boolean).map((code) => ({ value: code, label: code }))}
            onChange={(pickZone) => updateQuery({ pickZone })}
          />
        )}
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} task${page.totalElements === 1 ? '' : 's'}` : ''}
        </span>
      </div>

      {state.status === 'error' && (
        <div className="notice" role="alert">
          <p>{state.message}</p>
          <button type="button" className="button" onClick={() => setReloadKey((k) => k + 1)}>
            Try again
          </button>
        </div>
      )}

      {page && (
        <div className={`table-wrap${state.status === 'loading' ? ' table-wrap--loading' : ''}`}>
          <table className="table" aria-label="Pick tasks">
            <thead>
              <tr>
                <th scope="col">Task</th>
                <th scope="col">Method</th>
                {zoned && <th scope="col">Pick zone</th>}
                <th scope="col">Orders</th>
                <th scope="col" className="num">
                  Details
                </th>
                <th scope="col" className="num">
                  Lines
                </th>
                <th scope="col" className="num">
                  Locations
                </th>
                <th scope="col" className="num">
                  Quantity
                </th>
                <th scope="col">Status</th>
                <th scope="col">Picker</th>
                <th scope="col">Container</th>
                <th scope="col">Created</th>
              </tr>
            </thead>
            <tbody>
              {page.content.map((t) => (
                <tr key={t.id}>
                  <th scope="row">
                    <Link className="code" to={`/picking/tasks/${t.id}`}>
                      {t.taskNumber}
                    </Link>
                  </th>
                  <td>
                    <PickMethodBadge method={t.pickMethod} />
                  </td>
                  {zoned && <td>{t.pickZone ? <span className="code">{t.pickZone}</span> : <span className="muted">—</span>}</td>}
                  <td>
                    <OrderLinks task={t} />
                  </td>
                  <td className="num">{t.detailCount}</td>
                  <td className="num">{t.lineCount}</td>
                  <td className="num">{t.locationCount}</td>
                  <td className="num">{t.totalQuantity.toLocaleString()} EA</td>
                  <td>
                    <span className={`badge badge--task-${statusClass(t.status)}`}>{taskStatusLabel(t.status)}</span>
                  </td>
                  <td>{t.assignedTo ?? <span className="muted">—</span>}</td>
                  <td>
                    {t.toContainer ? <span className="code">{t.toContainer.lpnId}</span> : <span className="muted">—</span>}
                    {t.dropLocation && <span className="muted cell__sub">{t.dropLocation.locationId}</span>}
                  </td>
                  <td className="muted">{formatDateTime(t.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.content.length === 0 && (
            <div className="empty">
              <p>{query.q || query.status || query.pickMethod || query.pickZone ? 'No pick tasks match your search.' : 'No pick tasks yet. They are created when orders are allocated.'}</p>
            </div>
          )}
        </div>
      )}

      {page && page.totalElements > 0 && (
        <Pagination page={page} onPage={(p) => updateQuery({ page: p })} onSize={(s) => updateQuery({ size: s })} />
      )}
    </div>
  );
}

function readQuery(params: URLSearchParams): PickTaskQuery {
  const status = params.get('status');
  const method = params.get('pickMethod');
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    status: TASK_STATUSES.find((s) => s.value === status)?.value ?? '',
    pickMethod: method === 'DISCRETE' || method === 'BATCH' ? method : '',
    pickZone: params.get('pickZone') ?? '',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: PickTaskQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.status) params.set('status', q.status);
  if (q.pickMethod) params.set('pickMethod', q.pickMethod);
  if (q.pickZone) params.set('pickZone', q.pickZone);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}

const SHOWN_ORDERS = 2;

/** The task's orders: the first couple as links, then "+N more". */
function OrderLinks({ task }: { task: PickTaskView }) {
  const shown = task.orders.slice(0, SHOWN_ORDERS);
  const more = task.orders.length - shown.length;
  return (
    <>
      <span className="order-links">
        {shown.map((o, i) => (
          <span key={o.id}>
            {i > 0 && ', '}
            <Link className="code" to={`/outbound/orders/${o.id}`}>
              {o.orderNumber}
            </Link>
          </span>
        ))}
        {more > 0 && <span className="muted"> +{more} more</span>}
      </span>
      <span className="muted cell__sub">
        {task.orders.length === 1 ? task.orders[0].customerName : `${task.orders.length} orders`}
      </span>
    </>
  );
}
