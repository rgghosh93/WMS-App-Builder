import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import {
  ACTIVE_STATUSES,
  CONTAINER_CATEGORIES,
  categoryLabel,
  listContainerTypes,
  type ActiveStatus,
  type ContainerCategory,
  type ContainerType,
  type ContainerTypeQuery,
  type ContainerTypeSortField,
} from '../../api/containerTypes';
import { formatDimensions, formatVolume, formatWeight } from '../../api/measure';
import { errorMessage, type Page } from '../../api/http';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { SortableHeader, type Column } from '../../components/SortableHeader';
import { ContainerTypeForm } from './ContainerTypeForm';

const COLUMNS: Column<ContainerTypeSortField>[] = [
  { field: 'containerTypeId', label: 'Container type ID' },
  { field: 'description', label: 'Description' },
  { field: 'category', label: 'Category' },
  { field: 'status', label: 'Status' },
  { field: null, label: 'Reusable' },
  { field: null, label: 'Outer size (W × D × H)' },
  { field: null, label: 'Tare weight' },
  { field: null, label: 'Max load' },
  { field: null, label: 'Max volume' },
  { field: 'updatedAt', label: 'Updated' },
];

const SORT_FIELDS: ContainerTypeSortField[] = ['containerTypeId', 'description', 'category', 'status', 'updatedAt'];
const CATEGORY_VALUES = CONTAINER_CATEGORIES.map((c) => c.value);
const NONE = <span className="muted">—</span>;

type LoadState =
  | { status: 'loading'; previous?: Page<ContainerType> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<ContainerType> };

type Editing = null | { mode: 'new' } | { mode: 'edit'; containerType: ContainerType };

export function ContainerTypesPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const updateQuery = useCallback(
    (changes: Partial<ContainerTypeQuery>) => {
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
    listContainerTypes(readQuery(new URLSearchParams(queryKey)), controller.signal)
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
  const filtered = Boolean(query.q || query.category || query.status);

  const toggleSort = (field: ContainerTypeSortField) => {
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
        <span aria-current="page">Container Types</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Container Types</h1>
          <p className="muted">Kinds of containers that hold inventory: pallets, cartons, totes, bins and more.</p>
        </div>
        <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
          <Plus size={16} aria-hidden="true" />
          New container type
        </button>
      </header>

      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={searchText} onChange={setSearchText} placeholder="Search by ID or description…" label="Search container types" />
        </div>
        <label className="select-inline">
          <span>Category</span>
          <select value={query.category} onChange={(e) => updateQuery({ category: e.target.value as ContainerCategory | '' })}>
            <option value="">All</option>
            {CONTAINER_CATEGORIES.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
        </label>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as ActiveStatus | '' })}>
            <option value="">All</option>
            {ACTIVE_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} container type${page.totalElements === 1 ? '' : 's'}` : ''}
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
              {page.content.map((ct) => (
                <tr key={ct.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', containerType: ct })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', containerType: ct });
                      }}
                    >
                      {ct.containerTypeId}
                    </button>
                  </th>
                  <td className="cell--wrap">{ct.description}</td>
                  <td>{categoryLabel(ct.category)}</td>
                  <td>
                    <span className={`badge badge--${ct.status.toLowerCase()}`}>
                      {ACTIVE_STATUSES.find((s) => s.value === ct.status)?.label}
                    </span>
                  </td>
                  <td>{ct.reusable ? 'Yes' : 'No'}</td>
                  <td>{formatDimensions(ct)}</td>
                  <td>{formatWeight(ct.tareWeight, ct.tareWeightUom) || NONE}</td>
                  <td>{formatWeight(ct.maxWeight, ct.maxWeightUom) || NONE}</td>
                  <td>{formatVolume(ct.maxVolume, ct.maxVolumeUom) || NONE}</td>
                  <td className="muted">{formatDate(ct.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {page.content.length === 0 && (
            <div className="empty">
              {filtered ? (
                <p>No container types match your search.</p>
              ) : (
                <>
                  <p>No container types yet.</p>
                  <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
                    Create the first container type
                  </button>
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
        <ContainerTypeForm
          key={editing.mode === 'edit' ? editing.containerType.id : 'new'}
          containerType={editing.mode === 'edit' ? editing.containerType : null}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => {
            setEditing(null);
            setNotice(`${created ? 'Created' : 'Saved'} container type ${saved.containerTypeId}`);
            setReloadKey((k) => k + 1);
          }}
          onDeleted={(deleted) => {
            setEditing(null);
            setNotice(`Deleted container type ${deleted.containerTypeId}`);
            setReloadKey((k) => k + 1);
          }}
        />
      )}
    </div>
  );
}

function readQuery(params: URLSearchParams): ContainerTypeQuery {
  const sort = params.get('sort') as ContainerTypeSortField | null;
  const category = params.get('category') as ContainerCategory | null;
  const status = params.get('status');
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    category: category && CATEGORY_VALUES.includes(category) ? category : '',
    status: status === 'ACTIVE' || status === 'INACTIVE' ? status : '',
    sort: sort && SORT_FIELDS.includes(sort) ? sort : 'containerTypeId',
    dir: params.get('dir') === 'desc' ? 'desc' : 'asc',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: ContainerTypeQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.category) params.set('category', q.category);
  if (q.status) params.set('status', q.status);
  if (q.sort !== 'containerTypeId') params.set('sort', q.sort);
  if (q.dir !== 'asc') params.set('dir', q.dir);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}
