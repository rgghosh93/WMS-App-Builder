import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Package } from 'lucide-react';
import { errorMessage, type Page } from '../../api/http';
import { statusClass } from '../../api/orders';
import {
  PACK_STATUSES,
  listPackStations,
  listPackTasks,
  packStatusLabel,
  type PackStation,
  type PackTask,
  type PackTaskQuery,
  type PackTaskStatus,
} from '../../api/packing';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { formatDateTime } from '../outbound/OrdersPage';

type LoadState =
  | { status: 'loading'; previous?: Page<PackTask> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<PackTask> };

/** Every container dropped at a pack station and how far its packing is, newest first. */
export function PackTasksPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [stations, setStations] = useState<PackStation[]>([]);
  const [reloadKey, setReloadKey] = useState(0);

  const updateQuery = useCallback(
    (changes: Partial<PackTaskQuery>) => {
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

  useEffect(() => {
    const controller = new AbortController();
    // The filter still works without the list; it just has no stations to offer.
    listPackStations(controller.signal)
      .then(setStations)
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  const queryKey = params.toString();
  useEffect(() => {
    const controller = new AbortController();
    setState((current) => ({
      status: 'loading',
      previous: current.status === 'ready' ? current.page : current.status === 'loading' ? current.previous : undefined,
    }));
    listPackTasks(readQuery(new URLSearchParams(queryKey)), controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [queryKey, reloadKey]);

  const page = state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined;
  const filtered = Boolean(query.q || query.status || query.stationId);

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Pack and Ship</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Packing Tasks</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Packing Tasks</h1>
          <p className="muted">One task for each container dropped at a pack station. Open a task to see what is in it and the cartons made.</p>
        </div>
        <Link to="/pack" className="button button--primary">
          <Package size={16} aria-hidden="true" />
          Pack station
        </Link>
      </header>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox
            value={searchText}
            onChange={setSearchText}
            placeholder="Search task, container, carton, order or item…"
            label="Search packing tasks"
          />
        </div>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as PackTaskStatus | '' })}>
            <option value="">All</option>
            {PACK_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <label className="select-inline">
          <span>Station</span>
          <select value={query.stationId} onChange={(e) => updateQuery({ stationId: e.target.value })}>
            <option value="">All</option>
            {stations.map((s) => (
              <option key={s.id} value={s.id}>
                {s.locationId}
              </option>
            ))}
          </select>
        </label>
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
          <table className="table" aria-label="Packing tasks">
            <thead>
              <tr>
                <th scope="col">Task</th>
                <th scope="col">Station</th>
                <th scope="col">Container</th>
                <th scope="col">Orders</th>
                <th scope="col" className="num">
                  Packed
                </th>
                <th scope="col" className="num">
                  Cartons
                </th>
                <th scope="col">Status</th>
                <th scope="col">Packer</th>
                <th scope="col">Dropped</th>
              </tr>
            </thead>
            <tbody>
              {page.content.map((t) => (
                <tr key={t.id}>
                  <th scope="row">
                    <Link className="code" to={`/pack-ship/packing-tasks/${t.id}`}>
                      {t.taskNumber}
                    </Link>
                  </th>
                  <td className="code">{t.station.locationId}</td>
                  <td className="code">{t.container.lpnId}</td>
                  <td>
                    {t.orders.slice(0, 2).map((o, i) => (
                      <span key={o.id}>
                        {i > 0 && ', '}
                        <Link className="code" to={`/outbound/orders/${o.id}`}>
                          {o.orderNumber}
                        </Link>
                      </span>
                    ))}
                    {t.orders.length > 2 && <span className="muted"> +{t.orders.length - 2} more</span>}
                  </td>
                  <td className="num">
                    {t.packedQuantity.toLocaleString()} / {t.totalQuantity.toLocaleString()} EA
                    {t.shortQuantity > 0 && <span className="muted cell__sub">{t.shortQuantity} missing</span>}
                  </td>
                  <td className="num">{t.cartons.length}</td>
                  <td>
                    <span className={`badge badge--task-${statusClass(t.status)}`}>{packStatusLabel(t.status)}</span>
                  </td>
                  <td>{t.assignedTo ?? <span className="muted">—</span>}</td>
                  <td className="muted">{formatDateTime(t.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.content.length === 0 && (
            <div className="empty">
              <p>
                {filtered
                  ? 'No packing tasks match your search.'
                  : 'No packing tasks yet. They are created when pickers drop containers at pack stations.'}
              </p>
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

function readQuery(params: URLSearchParams): PackTaskQuery {
  const status = params.get('status');
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    status: PACK_STATUSES.find((s) => s.value === status)?.value ?? '',
    stationId: params.get('station') ?? '',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: PackTaskQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.status) params.set('status', q.status);
  if (q.stationId) params.set('station', q.stationId);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}
