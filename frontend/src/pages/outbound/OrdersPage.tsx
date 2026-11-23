import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Layers, Plus } from 'lucide-react';
import {
  ORDER_STATUSES,
  batchAllocate,
  canAllocate,
  listOrders,
  orderStatusLabel,
  statusClass,
  type OrderQuery,
  type OrderSortField,
  type OrderStatus,
  type OrderSummary,
} from '../../api/orders';
import { errorMessage, type Page } from '../../api/http';
import { useOwners } from '../../api/owners';
import { OwnerFilter } from '../../components/OwnerSelect';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { SortableHeader, type Column } from '../../components/SortableHeader';
import { Can, useCan } from '../../session/SessionContext';
import { NewOrderForm } from './NewOrderForm';

const COLUMNS: Column<OrderSortField>[] = [
  { field: 'orderNumber', label: 'Order' },
  { field: 'customerName', label: 'Customer' },
  { field: 'status', label: 'Status' },
  { field: null, label: 'Lines', numeric: true },
  { field: null, label: 'Allocated' },
  { field: null, label: 'Open tasks', numeric: true },
  { field: 'requestedShipDate', label: 'Ship by' },
  { field: 'priority', label: 'Priority', numeric: true },
  { field: 'createdAt', label: 'Received' },
];

const SORT_FIELDS: OrderSortField[] = ['createdAt', 'orderNumber', 'customerName', 'requestedShipDate', 'priority', 'status'];
const STATUS_VALUES = ORDER_STATUSES.map((s) => s.value);

type LoadState =
  | { status: 'loading'; previous?: Page<OrderSummary> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<OrderSummary> };

export function OrdersPage() {
  const navigate = useNavigate();
  const may = useCan('ORDERS');
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [creating, setCreating] = useState(false);
  const { several } = useOwners();
  // Orders picked to allocate together on one batch pick task. Kept across pages and searches.
  const [selected, setSelected] = useState<Map<string, string>>(new Map());
  const [batching, setBatching] = useState(false);
  const [batchError, setBatchError] = useState<string | null>(null);
  const [batchNotice, setBatchNotice] = useState<ReactNode>(null);

  const toggle = (o: OrderSummary) =>
    setSelected((current) => {
      const next = new Map(current);
      if (next.has(o.id)) next.delete(o.id);
      else next.set(o.id, o.orderNumber);
      return next;
    });

  const runBatch = async () => {
    setBatching(true);
    setBatchError(null);
    try {
      const result = await batchAllocate([...selected.keys()]);
      setSelected(new Map());
      if (result.tasks.length > 1) {
        setBatchNotice(
          <>
            Created {result.tasks.length} pick tasks, one for each pick zone:{' '}
            {result.tasks.map((t, i) => (
              <span key={t.id}>
                {i > 0 && ', '}
                <Link className="code" to={`/picking/tasks/${t.id}`}>
                  {t.taskNumber}
                </Link>{' '}
                ({t.pickZone ?? 'no zone'})
              </span>
            ))}
            .
          </>,
        );
        setReloadKey((k) => k + 1);
      } else if (result.task) {
        navigate(`/picking/tasks/${result.task.id}`);
      } else {
        setBatchNotice('No stock could be reserved for these orders, so no pick task was created.');
        setReloadKey((k) => k + 1);
      }
    } catch (error) {
      setBatchError(errorMessage(error));
    } finally {
      setBatching(false);
    }
  };

  const updateQuery = useCallback(
    (changes: Partial<OrderQuery>) => {
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
    listOrders(readQuery(new URLSearchParams(queryKey)), controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [queryKey, reloadKey]);

  const page = state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined;
  const filtered = Boolean(query.q || query.status || query.owner);

  const toggleSort = (field: OrderSortField) => {
    const dir = query.sort === field && query.dir === 'desc' ? 'asc' : 'desc';
    updateQuery({ sort: field, dir, page: query.page });
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Outbound</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Orders</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Orders</h1>
          <p className="muted">Customer orders. Each is allocated when it arrives and gets a pick task. Select orders that still need stock to allocate them together on one batch pick task.</p>
        </div>
        <Can permission="ORDERS">
          <button type="button" className="button button--primary" onClick={() => setCreating(true)}>
            <Plus size={16} aria-hidden="true" />
            New order
          </button>
        </Can>
      </header>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={searchText} onChange={setSearchText} placeholder="Search order number or customer…" label="Search orders" />
        </div>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as OrderStatus | '' })}>
            <option value="">All</option>
            {ORDER_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <OwnerFilter value={query.owner ?? ''} onChange={(owner) => updateQuery({ owner })} />
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} order${page.totalElements === 1 ? '' : 's'}` : ''}
        </span>
      </div>

      {selected.size > 0 && (
        <div className="selection-bar" role="group" aria-label="Selected orders">
          <span>
            <strong>{selected.size}</strong> order{selected.size === 1 ? '' : 's'} selected:{' '}
            <span className="code">{[...selected.values()].join(', ')}</span>
          </span>
          <div className="button-row">
            <button type="button" className="button" onClick={() => setSelected(new Map())} disabled={batching}>
              Clear
            </button>
            <button
              type="button"
              className="button button--primary"
              onClick={runBatch}
              disabled={batching || selected.size < 2}
              title={selected.size < 2 ? 'Select at least two orders' : undefined}
            >
              <Layers size={16} aria-hidden="true" />
              {batching ? 'Allocating…' : 'Batch allocate'}
            </button>
          </div>
        </div>
      )}
      {batchError && (
        <div className="form__error" role="alert">
          {batchError}
        </div>
      )}
      {batchNotice && (
        <div className="notice-info" role="status">
          {batchNotice}
        </div>
      )}

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
          <table className="table">
            <SortableHeader
              columns={COLUMNS}
              sort={query.sort}
              dir={query.dir}
              onSort={toggleSort}
              leading={<span className="visually-hidden">Select for batch</span>}
            />
            <tbody>
              {page.content.map((o) => (
                <tr key={o.id} className="table__row--link" onClick={() => navigate(`/outbound/orders/${o.id}`)}>
                  <td className="table__select" onClick={(e) => e.stopPropagation()}>
                    {may && canAllocate(o) && (
                      <input
                        type="checkbox"
                        aria-label={`Select ${o.orderNumber} for batch`}
                        checked={selected.has(o.id)}
                        onChange={() => toggle(o)}
                      />
                    )}
                  </td>
                  <th scope="row">
                    <Link className="link-button code" to={`/outbound/orders/${o.id}`} onClick={(e) => e.stopPropagation()}>
                      {o.orderNumber}
                    </Link>
                    {several && o.owner && <span className="muted cell__sub">{o.owner.code}</span>}
                  </th>
                  <td>{o.customerName}</td>
                  <td>
                    <span className={`badge badge--order-${statusClass(o.status)}`}>{orderStatusLabel(o.status)}</span>
                  </td>
                  <td className="num">{o.lineCount}</td>
                  <td>
                    <AllocationBar allocated={o.allocatedQuantity} ordered={o.orderedQuantity} />
                  </td>
                  <td className="num">{o.openTaskCount}</td>
                  <td>{o.requestedShipDate ? formatDay(o.requestedShipDate) : <span className="muted">—</span>}</td>
                  <td className="num">{o.priority}</td>
                  <td className="muted">{formatDateTime(o.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.content.length === 0 && (
            <div className="empty">
              {filtered ? (
                <p>No orders match your search.</p>
              ) : (
                <>
                  <p>No orders yet.</p>
                  <Can permission="ORDERS">
                    <button type="button" className="button button--primary" onClick={() => setCreating(true)}>
                      Create the first order
                    </button>
                  </Can>
                </>
              )}
            </div>
          )}
        </div>
      )}

      {page && page.totalElements > 0 && (
        <Pagination page={page} onPage={(p) => updateQuery({ page: p })} onSize={(s) => updateQuery({ size: s })} />
      )}

      {creating && (
        <NewOrderForm
          onClose={() => setCreating(false)}
          onCreated={(order) => navigate(`/outbound/orders/${order.id}`, { state: { created: true } })}
        />
      )}
    </div>
  );
}

/** "27 / 30 EA" with a small bar showing how much of the order has stock reserved. */
export function AllocationBar({ allocated, ordered, word = 'allocated' }: { allocated: number; ordered: number; word?: string }) {
  const pct = ordered === 0 ? 0 : Math.round((allocated / ordered) * 100);
  return (
    <span className="alloc" title={`${pct}% ${word}`}>
      <span className="alloc__text">
        {allocated.toLocaleString()} / {ordered.toLocaleString()} EA
      </span>
      <span className="alloc__bar" role="presentation">
        <span className={`alloc__fill${pct >= 100 ? ' alloc__fill--full' : ''}`} style={{ width: `${Math.min(pct, 100)}%` }} />
      </span>
    </span>
  );
}

function readQuery(params: URLSearchParams): OrderQuery {
  const sort = params.get('sort') as OrderSortField | null;
  const status = params.get('status') as OrderStatus | null;
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    owner: params.get('owner') ?? '',
    status: status && STATUS_VALUES.includes(status) ? status : '',
    sort: sort && SORT_FIELDS.includes(sort) ? sort : 'createdAt',
    dir: params.get('dir') === 'asc' ? 'asc' : 'desc',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: OrderQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.owner) params.set('owner', q.owner);
  if (q.status) params.set('status', q.status);
  if (q.sort !== 'createdAt') params.set('sort', q.sort);
  if (q.dir !== 'desc') params.set('dir', q.dir);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}

export function formatDay(value: string): string {
  return new Date(`${value}T00:00:00`).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

export function formatDateTime(value: string): string {
  return new Date(value).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });
}
