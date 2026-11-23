import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import {
  ITEM_STATUSES,
  listItems,
  packagingSummary,
  trackingLabels,
  type Item,
  type ItemQuery,
  type ItemSortField,
  type ItemStatus,
} from '../../api/items';
import { listUoms, type Uom } from '../../api/uoms';
import { formatDimensions, formatWeight } from '../../api/measure';
import { errorMessage, type Page } from '../../api/http';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { SortableHeader, type Column } from '../../components/SortableHeader';
import { Can } from '../../session/SessionContext';
import { ItemForm } from './ItemForm';

const COLUMNS: Column<ItemSortField>[] = [
  { field: 'itemId', label: 'Item ID' },
  { field: 'description', label: 'Description' },
  { field: 'status', label: 'Status' },
  { field: 'barcode', label: 'Barcode' },
  { field: null, label: 'Tracking' },
  { field: null, label: 'Each (W × D × H)' },
  { field: null, label: 'Each weight' },
  { field: null, label: 'Packaging (eaches per unit)' },
  { field: 'updatedAt', label: 'Updated' },
];

const SORT_FIELDS: ItemSortField[] = ['itemId', 'description', 'status', 'barcode', 'updatedAt'];

type LoadState =
  | { status: 'loading'; previous?: Page<Item> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<Item> };

type Editing = null | { mode: 'new' } | { mode: 'edit'; item: Item };

export function ItemsPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [units, setUnits] = useState<Uom[]>([]);

  useEffect(() => {
    const controller = new AbortController();
    listUoms(controller.signal)
      .then(setUnits)
      .catch(() => {
        /* the list still works; the form just can't offer units */
      });
    return () => controller.abort();
  }, []);

  const updateQuery = useCallback(
    (changes: Partial<ItemQuery>) => {
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
    listItems(readQuery(new URLSearchParams(queryKey)), controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [queryKey, reloadKey]);

  useEffect(() => {
    if (!notice) return;
    const handle = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(handle);
  }, [notice]);

  const page = state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined;
  const filtered = Boolean(query.q || query.status);

  const toggleSort = (field: ItemSortField) => {
    const dir = query.sort === field && query.dir === 'asc' ? 'desc' : 'asc';
    updateQuery({ sort: field, dir, page: query.page });
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Master Data</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Items</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Items</h1>
          <p className="muted">Products the warehouse stores and ships, with barcodes, tracking and unit size.</p>
        </div>
        <Can permission="MASTER_DATA">
          <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
            <Plus size={16} aria-hidden="true" />
            New item
          </button>
        </Can>
      </header>

      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={searchText} onChange={setSearchText} placeholder="Search by ID, description, barcode…" label="Search items" />
        </div>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as ItemStatus | '' })}>
            <option value="">All</option>
            {ITEM_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} item${page.totalElements === 1 ? '' : 's'}` : ''}
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
          <table className="table">
            <SortableHeader columns={COLUMNS} sort={query.sort} dir={query.dir} onSort={toggleSort} />
            <tbody>
              {page.content.map((item) => (
                <tr key={item.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', item })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', item });
                      }}
                    >
                      {item.itemId}
                    </button>
                  </th>
                  <td className="cell--wrap">{item.description}</td>
                  <td>
                    <span className={`badge badge--${item.status.toLowerCase()}`}>
                      {ITEM_STATUSES.find((s) => s.value === item.status)?.label}
                    </span>
                  </td>
                  <td className="code">{item.barcode ?? <span className="muted">—</span>}</td>
                  <td>
                    {trackingLabels(item).length ? (
                      <span className="tag-list">
                        {trackingLabels(item).map((t) => (
                          <span key={t} className="tag">
                            {t}
                          </span>
                        ))}
                      </span>
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                  <td>{formatDimensions(item) || <span className="muted">—</span>}</td>
                  <td>{formatWeight(item.weight, item.weightUom) || <span className="muted">—</span>}</td>
                  <td>{packagingSummary(item.uoms) || <span className="muted">—</span>}</td>
                  <td className="muted">{formatDate(item.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {page.content.length === 0 && (
            <div className="empty">
              {filtered ? (
                <p>No items match your search.</p>
              ) : (
                <>
                  <p>No items yet.</p>
                  <Can permission="MASTER_DATA">
                    <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
                      Create the first item
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

      {editing && (
        <ItemForm
          key={editing.mode === 'edit' ? editing.item.id : 'new'}
          item={editing.mode === 'edit' ? editing.item : null}
          units={units}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => {
            setEditing(null);
            setNotice(`${created ? 'Created' : 'Saved'} item ${saved.itemId}`);
            setReloadKey((k) => k + 1);
          }}
          onDeleted={(deleted) => {
            setEditing(null);
            setNotice(`Deleted item ${deleted.itemId}`);
            setReloadKey((k) => k + 1);
          }}
        />
      )}
    </div>
  );
}

function readQuery(params: URLSearchParams): ItemQuery {
  const sort = params.get('sort') as ItemSortField | null;
  const status = params.get('status');
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    status: status === 'ACTIVE' || status === 'INACTIVE' ? status : '',
    sort: sort && SORT_FIELDS.includes(sort) ? sort : 'itemId',
    dir: params.get('dir') === 'desc' ? 'desc' : 'asc',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: ItemQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.status) params.set('status', q.status);
  if (q.sort !== 'itemId') params.set('sort', q.sort);
  if (q.dir !== 'asc') params.set('dir', q.dir);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}
