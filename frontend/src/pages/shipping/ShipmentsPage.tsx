import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { listCarriers, type Carrier } from '../../api/carriers';
import { errorMessage, type Page } from '../../api/http';
import {
  SHIPMENT_STATUSES,
  listShipments,
  shipmentStatusLabel,
  shippingBadge,
  type Shipment,
  type ShipmentQuery,
  type ShipmentStatus,
} from '../../api/shipping';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { formatDateTime } from '../outbound/OrdersPage';

type LoadState =
  | { status: 'loading'; previous?: Page<Shipment> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<Shipment> };

/** Packed cartons going out, newest first, and what each shipment still needs. */
export function ShipmentsPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);
  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [carriers, setCarriers] = useState<Carrier[]>([]);
  const [reloadKey, setReloadKey] = useState(0);

  const updateQuery = useCallback(
    (changes: Partial<ShipmentQuery>) => {
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
    // Without the list, the carrier filter just has nothing to offer.
    listCarriers(controller.signal)
      .then(setCarriers)
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
    listShipments(readQuery(new URLSearchParams(queryKey)), controller.signal)
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
        <span aria-current="page">Shipments</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Shipments</h1>
          <p className="muted">
            Closed cartons of an order go on its shipment. Give it an address, a carrier service and tracking numbers, then load it at a
            dock door.
          </p>
        </div>
      </header>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox
            value={searchText}
            onChange={setSearchText}
            placeholder="Search shipment, order, customer, carton or tracking…"
            label="Search shipments"
          />
        </div>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as ShipmentStatus | '' })}>
            <option value="">All</option>
            {SHIPMENT_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <label className="select-inline">
          <span>Carrier</span>
          <select value={query.carrierId} onChange={(e) => updateQuery({ carrierId: e.target.value })}>
            <option value="">All</option>
            {carriers.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} shipment${page.totalElements === 1 ? '' : 's'}` : ''}
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
          <table className="table" aria-label="Shipments">
            <thead>
              <tr>
                <th scope="col">Shipment</th>
                <th scope="col">Order</th>
                <th scope="col">Ship to</th>
                <th scope="col">Carrier</th>
                <th scope="col" className="num">
                  Cartons
                </th>
                <th scope="col" className="num">
                  Quantity
                </th>
                <th scope="col">Status</th>
                <th scope="col">Manifest</th>
                <th scope="col">Shipped</th>
              </tr>
            </thead>
            <tbody>
              {page.content.map((s) => (
                <tr key={s.id}>
                  <th scope="row">
                    <Link className="code" to={`/pack-ship/shipments/${s.id}`}>
                      {s.shipmentNumber}
                    </Link>
                  </th>
                  <td>
                    <Link className="code" to={`/outbound/orders/${s.order.id}`}>
                      {s.order.orderNumber}
                    </Link>
                    <span className="muted cell__sub">{s.order.customerName}</span>
                  </td>
                  <td>
                    {s.shipTo ? (
                      `${s.shipTo.city}${s.shipTo.state ? `, ${s.shipTo.state}` : ''}`
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                  <td>
                    {s.carrier ? s.carrier.name : <span className="muted">—</span>}
                    {s.service && <span className="muted cell__sub">{s.service.name}</span>}
                  </td>
                  <td className="num">{s.status === 'LOADING' ? `${s.loadedCount} / ${s.cartons.length}` : s.cartons.length}</td>
                  <td className="num">{s.totalQuantity.toLocaleString()} EA</td>
                  <td>
                    <span className={`badge ${shippingBadge(s.status)}`}>{shipmentStatusLabel(s.status)}</span>
                    {s.status === 'OPEN' && (
                      <span className="muted cell__sub">{s.missing.length === 0 ? 'Ready to load' : 'Not ready'}</span>
                    )}
                  </td>
                  <td>
                    {s.manifest ? (
                      <Link className="code" to={`/pack-ship/manifests/${s.manifest.id}`}>
                        {s.manifest.manifestNumber}
                      </Link>
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                  <td className="muted">{s.shippedAt ? formatDateTime(s.shippedAt) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.content.length === 0 && (
            <div className="empty">
              <p>
                {query.q || query.status || query.carrierId
                  ? 'No shipments match your search.'
                  : 'No shipments yet. They are made when cartons are closed at a pack station.'}
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

function readQuery(params: URLSearchParams): ShipmentQuery {
  const status = params.get('status');
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    status: SHIPMENT_STATUSES.find((s) => s.value === status)?.value ?? '',
    carrierId: params.get('carrier') ?? '',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: ShipmentQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.status) params.set('status', q.status);
  if (q.carrierId) params.set('carrier', q.carrierId);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}
