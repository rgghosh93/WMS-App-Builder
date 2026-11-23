import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Truck } from 'lucide-react';
import { errorMessage, type Page } from '../../api/http';
import { listManifests, shippingBadge, type Manifest, type ManifestQuery, type ManifestStatus } from '../../api/shipping';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { formatDateTime } from '../outbound/OrdersPage';

type LoadState =
  | { status: 'loading'; previous?: Page<Manifest> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<Manifest> };

/** What was loaded with each carrier, newest first. */
export function ManifestsPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);
  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);

  const updateQuery = useCallback(
    (changes: Partial<ManifestQuery>) => {
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
    listManifests(readQuery(new URLSearchParams(queryKey)), controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [queryKey, reloadKey]);

  const page = state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined;

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Pack and Ship</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Manifests</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Manifests</h1>
          <p className="muted">What leaves with one carrier from one dock door. Closing a manifest ships everything loaded on it.</p>
        </div>
        <Link to="/rf/loading" className="button button--primary">
          <Truck size={16} aria-hidden="true" />
          Load at a dock door
        </Link>
      </header>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox
            value={searchText}
            onChange={setSearchText}
            placeholder="Search manifest, carrier, door, trailer, shipment or order…"
            label="Search manifests"
          />
        </div>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as ManifestStatus | '' })}>
            <option value="">All</option>
            <option value="OPEN">Open</option>
            <option value="CLOSED">Closed</option>
          </select>
        </label>
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} manifest${page.totalElements === 1 ? '' : 's'}` : ''}
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
          <table className="table" aria-label="Manifests">
            <thead>
              <tr>
                <th scope="col">Manifest</th>
                <th scope="col">Carrier</th>
                <th scope="col">Door</th>
                <th scope="col" className="num">
                  Shipments
                </th>
                <th scope="col" className="num">
                  Cartons
                </th>
                <th scope="col">Trailer</th>
                <th scope="col">Status</th>
                <th scope="col">Opened</th>
                <th scope="col">Closed</th>
              </tr>
            </thead>
            <tbody>
              {page.content.map((m) => (
                <tr key={m.id}>
                  <th scope="row">
                    <Link className="code" to={`/pack-ship/manifests/${m.id}`}>
                      {m.manifestNumber}
                    </Link>
                  </th>
                  <td>{m.carrier.name}</td>
                  <td className="code">{m.door.locationId}</td>
                  <td className="num">{m.shipments.length}</td>
                  <td className="num">{m.cartonCount}</td>
                  <td className="code">{m.trailerNumber ?? <span className="muted">—</span>}</td>
                  <td>
                    <span className={`badge ${shippingBadge(m.status)}`}>{m.status === 'OPEN' ? 'Open' : 'Closed'}</span>
                  </td>
                  <td className="muted">{formatDateTime(m.createdAt)}</td>
                  <td className="muted">{m.closedAt ? formatDateTime(m.closedAt) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.content.length === 0 && (
            <div className="empty">
              <p>
                {query.q || query.status
                  ? 'No manifests match your search.'
                  : 'No manifests yet. One is opened when loading starts at a dock door.'}
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

function readQuery(params: URLSearchParams): ManifestQuery {
  const status = params.get('status');
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    status: status === 'OPEN' || status === 'CLOSED' ? status : '',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: ManifestQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.status) params.set('status', q.status);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}
