import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { errorMessage, type Page } from '../../api/http';
import { statusClass } from '../../api/orders';
import { PUTAWAY_STATUSES, listPutawayTasks, putawayStatusLabel, type PutawayTask, type PutawayTaskQuery, type PutawayTaskStatus } from '../../api/putaway';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { formatDateTime } from '../outbound/OrdersPage';

type LoadState =
  | { status: 'loading'; previous?: Page<PutawayTask> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<PutawayTask> };

/** Every received LPN and where it is on its way to storage, newest first. */
export function PutawayTasksPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);

  const updateQuery = useCallback(
    (changes: Partial<PutawayTaskQuery>) => {
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
    listPutawayTasks(readQuery(new URLSearchParams(queryKey)), controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [queryKey, reloadKey]);

  const page = state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined;
  const filtered = Boolean(query.q || query.status);

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Put-away</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Put-away Tasks</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Put-away Tasks</h1>
          <p className="muted">
            One task for each LPN received. It is worked on a mobile device under <Link to="/rf/putaway">Put-away</Link>; the{' '}
            <Link to="/putaway/rules">rules</Link> decide where it goes.
          </p>
        </div>
      </header>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={searchText} onChange={setSearchText} placeholder="Search task, LPN, location or item…" label="Search put-away tasks" />
        </div>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as PutawayTaskStatus | '' })}>
            <option value="">All</option>
            {PUTAWAY_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
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
          <table className="table" aria-label="Put-away tasks">
            <thead>
              <tr>
                <th scope="col">Task</th>
                <th scope="col">LPN</th>
                <th scope="col">Contents</th>
                <th scope="col">From</th>
                <th scope="col">To</th>
                <th scope="col">Status</th>
                <th scope="col">Who</th>
                <th scope="col">Received</th>
              </tr>
            </thead>
            <tbody>
              {page.content.map((t) => (
                <tr key={t.id}>
                  <th scope="row">
                    <Link className="code" to={`/putaway/tasks/${t.id}`}>
                      {t.taskNumber}
                    </Link>
                  </th>
                  <td className="code">{t.lpn.lpnId}</td>
                  <td>
                    <Contents task={t} />
                  </td>
                  <td className="code">{t.fromLocation.locationId}</td>
                  <td>
                    {t.toLocation ? (
                      <span className="code">{t.toLocation.locationId}</span>
                    ) : t.directedLocation ? (
                      <span className="muted code">{t.directedLocation.locationId}</span>
                    ) : (
                      <span className="muted">—</span>
                    )}
                    {t.overrideReason && <span className="muted cell__sub">not {t.directedLocation?.locationId}</span>}
                  </td>
                  <td>
                    <span className={`badge badge--task-${statusClass(t.status)}`}>{putawayStatusLabel(t.status)}</span>
                  </td>
                  <td>{t.assignedTo ?? <span className="muted">—</span>}</td>
                  <td className="muted">{formatDateTime(t.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.content.length === 0 && (
            <div className="empty">
              <p>{filtered ? 'No put-away tasks match your search.' : 'No put-away tasks yet. They are created when LPNs are received.'}</p>
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

function Contents({ task }: { task: PutawayTask }) {
  const items = [...new Set(task.lines.map((l) => l.item.itemId))];
  if (items.length === 0) return <span className="muted">—</span>;
  return (
    <>
      <span className="code">{items[0]}</span>
      {items.length > 1 && <span className="muted"> +{items.length - 1} more</span>}
      <span className="muted cell__sub">{task.totalQuantity.toLocaleString()} EA</span>
    </>
  );
}

function readQuery(params: URLSearchParams): PutawayTaskQuery {
  const status = params.get('status');
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    status: PUTAWAY_STATUSES.find((s) => s.value === status)?.value ?? '',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: PutawayTaskQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.status) params.set('status', q.status);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}
