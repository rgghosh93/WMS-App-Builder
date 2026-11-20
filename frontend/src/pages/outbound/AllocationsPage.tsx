import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { AlertTriangle } from 'lucide-react';
import {
  ORDER_STATUSES,
  listAllocations,
  orderStatusLabel,
  statusClass,
  taskStatusLabel,
  type AllocationList,
  type AllocationQuery,
  type OrderStatus,
} from '../../api/orders';
import { INVENTORY_STATUSES, inventoryStatusLabel, type InventoryStatus } from '../../api/inventory';
import { errorMessage } from '../../api/http';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { formatDateTime, formatDay } from './OrdersPage';

type LoadState =
  | { status: 'loading'; previous?: AllocationList }
  | { status: 'error'; message: string }
  | { status: 'ready'; list: AllocationList };

const ORDER_STATUS_VALUES = ORDER_STATUSES.map((s) => s.value);
const INVENTORY_STATUS_VALUES = INVENTORY_STATUSES.map((s) => s.value);

/** Every reservation of stock for an order: what, for whom, where it is, and the pick task made for it. */
export function AllocationsPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);

  const updateQuery = useCallback(
    (changes: Partial<AllocationQuery>) => {
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
      previous: current.status === 'ready' ? current.list : current.status === 'loading' ? current.previous : undefined,
    }));
    listAllocations(readQuery(new URLSearchParams(queryKey)), controller.signal)
      .then((list) => setState({ status: 'ready', list }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [queryKey, reloadKey]);

  const list = state.status === 'ready' ? state.list : state.status === 'loading' ? state.previous : undefined;
  const filtered = Boolean(query.q || query.orderStatus || query.inventoryStatus);

  return (
    <div className="page page--wide">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Outbound</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Allocations</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Allocations</h1>
          <p className="muted">
            Stock reserved for orders. Each allocation holds a quantity of one inventory record for an order line and has a pick task.
          </p>
        </div>
        {list && (
          <dl className="summary">
            <div>
              <dt>Reserved, all orders</dt>
              <dd>
                {list.totalAllocatedQuantity.toLocaleString()} <span className="summary__unit">EA</span>
              </dd>
            </div>
          </dl>
        )}
      </header>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox
            value={searchText}
            onChange={setSearchText}
            placeholder="Search order, customer, item, lot, location, LPN…"
            label="Search allocations"
          />
        </div>
        <label className="select-inline">
          <span>Order status</span>
          <select value={query.orderStatus} onChange={(e) => updateQuery({ orderStatus: e.target.value as OrderStatus | '' })}>
            <option value="">All</option>
            {ORDER_STATUSES.filter((s) => s.value !== 'CANCELLED' && s.value !== 'NEW').map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <label className="select-inline">
          <span>Stock status</span>
          <select value={query.inventoryStatus} onChange={(e) => updateQuery({ inventoryStatus: e.target.value as InventoryStatus | '' })}>
            <option value="">All</option>
            {INVENTORY_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <span className="toolbar__count muted" aria-live="polite">
          {list ? `${list.totalElements.toLocaleString()} allocation${list.totalElements === 1 ? '' : 's'}` : ''}
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

      {list && (
        <div className={`table-wrap${state.status === 'loading' ? ' table-wrap--loading' : ''}`}>
          <table className="table" aria-label="Allocations">
            <thead>
              <tr>
                <th scope="col">Order</th>
                <th scope="col">Item</th>
                <th scope="col" className="num">
                  Quantity
                </th>
                <th scope="col">Location</th>
                <th scope="col">LPN</th>
                <th scope="col">Lot / serial</th>
                <th scope="col">Expiry</th>
                <th scope="col">Stock status</th>
                <th scope="col">Pick task</th>
                <th scope="col">Allocated</th>
              </tr>
            </thead>
            <tbody>
              {list.content.map((a) => {
                const blocked = a.inventoryStatus !== 'AVAILABLE';
                return (
                  <tr key={a.id}>
                    <th scope="row">
                      <Link className="code link-button" to={`/outbound/orders/${a.order.id}`}>
                        {a.order.orderNumber}
                      </Link>
                      <span className="muted cell__sub">
                        Line {a.lineNumber} · {a.order.customerName}
                      </span>
                      <span className={`badge badge--sm badge--order-${statusClass(a.order.status)}`}>{orderStatusLabel(a.order.status)}</span>
                    </th>
                    <td>
                      <span className="code">{a.item.itemId}</span>
                      <span className="muted cell__sub">{a.item.description}</span>
                    </td>
                    <td className="num">{a.quantity.toLocaleString()} EA</td>
                    <td>
                      {a.location ? (
                        <Link className="code" to={`/inventory/by-location?location=${a.location.id}`}>
                          {a.location.locationId}
                        </Link>
                      ) : (
                        <span className="muted">—</span>
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
                    <td className="code">{a.serialNumber ?? a.lotNumber ?? <span className="muted">—</span>}</td>
                    <td>{a.expiryDate ? formatDay(a.expiryDate) : <span className="muted">—</span>}</td>
                    <td>
                      {blocked ? (
                        <span className="stock-warning" title="This stock was put on hold or marked damaged after it was allocated">
                          <AlertTriangle size={14} aria-hidden="true" />
                          {inventoryStatusLabel(a.inventoryStatus)}
                        </span>
                      ) : (
                        inventoryStatusLabel(a.inventoryStatus)
                      )}
                    </td>
                    <td>
                      {a.task ? (
                        <>
                          <Link className="code" to={`/picking/tasks/${a.task.id}`}>
                            {a.task.taskNumber}
                          </Link>
                          <span className="muted"> · detail {a.task.detailNumber}</span>{' '}
                          <span className={`badge badge--sm badge--task-${statusClass(a.task.status)}`}>{taskStatusLabel(a.task.status)}</span>
                        </>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                    <td className="muted">{formatDateTime(a.createdAt)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {list.content.length === 0 && (
            <div className="empty">
              <p>{filtered ? 'No allocations match your search.' : 'No stock is reserved for any order.'}</p>
            </div>
          )}
        </div>
      )}

      {list && list.totalElements > 0 && (
        <Pagination page={list} onPage={(p) => updateQuery({ page: p })} onSize={(s) => updateQuery({ size: s })} />
      )}
    </div>
  );
}

function readQuery(params: URLSearchParams): AllocationQuery {
  const orderStatus = params.get('orderStatus') as OrderStatus | null;
  const inventoryStatus = params.get('stock') as InventoryStatus | null;
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    orderStatus: orderStatus && ORDER_STATUS_VALUES.includes(orderStatus) ? orderStatus : '',
    inventoryStatus: inventoryStatus && INVENTORY_STATUS_VALUES.includes(inventoryStatus) ? inventoryStatus : '',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: AllocationQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.orderStatus) params.set('orderStatus', q.orderStatus);
  if (q.inventoryStatus) params.set('stock', q.inventoryStatus);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}
