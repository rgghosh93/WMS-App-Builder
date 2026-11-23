import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ListPlus, Plus } from 'lucide-react';
import {
  CONTAINER_STATUSES,
  containerStatusLabel,
  listContainers,
  type Container,
  type ContainerQuery,
  type ContainerSortField,
  type ContainerStatus,
} from '../../api/containers';
import { categoryLabel, listContainerTypes, type ContainerType } from '../../api/containerTypes';
import { errorMessage, type Page } from '../../api/http';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { SortableHeader, type Column } from '../../components/SortableHeader';
import { Can } from '../../session/SessionContext';
import { ContainerForm } from './ContainerForm';
import { ContainerRangeForm } from './ContainerRangeForm';

const COLUMNS: Column<ContainerSortField>[] = [
  { field: 'lpnId', label: 'LPN' },
  { field: 'containerType', label: 'Container type' },
  { field: null, label: 'Category' },
  { field: 'status', label: 'Status' },
  { field: 'location', label: 'Current location' },
  { field: null, label: 'Notes' },
  { field: 'updatedAt', label: 'Updated' },
];

const SORT_FIELDS: ContainerSortField[] = ['lpnId', 'containerType', 'status', 'location', 'updatedAt'];
const STATUS_VALUES = CONTAINER_STATUSES.map((s) => s.value);
const NONE = <span className="muted">—</span>;

type LoadState =
  | { status: 'loading'; previous?: Page<Container> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<Container> };

type Editing = null | { mode: 'new' } | { mode: 'range' } | { mode: 'edit'; container: Container };

export function ContainersPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [types, setTypes] = useState<ContainerType[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const updateQuery = useCallback(
    (changes: Partial<ContainerQuery>) => {
      setParams((current) => writeQuery({ ...readQuery(current), page: 0, ...changes }), { replace: true });
    },
    [setParams],
  );

  // Reusable container types, for the filter and the forms.
  useEffect(() => {
    const controller = new AbortController();
    listContainerTypes({ q: '', category: '', status: '', page: 0, size: 200, sort: 'containerTypeId', dir: 'asc' }, controller.signal)
      .then((page) => setTypes(page.content.filter((t) => t.reusable)))
      .catch(() => {
        /* the list still works without the type filter */
      });
    return () => controller.abort();
  }, []);

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
    listContainers(readQuery(new URLSearchParams(queryKey)), controller.signal)
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
  const filtered = Boolean(query.q || query.containerTypeId || query.status);

  const toggleSort = (field: ContainerSortField) => {
    const dir = query.sort === field && query.dir === 'asc' ? 'desc' : 'asc';
    updateQuery({ sort: field, dir, page: query.page });
  };

  const done = (message: string) => {
    setEditing(null);
    setNotice(message);
    setReloadKey((k) => k + 1);
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Master Data</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Containers</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Containers</h1>
          <p className="muted">Permanent containers in the facility, such as totes and ASRS bins, identified by LPN.</p>
        </div>
        <div className="button-row">
          <Can permission="MASTER_DATA">
            <button type="button" className="button" onClick={() => setEditing({ mode: 'range' })}>
              <ListPlus size={16} aria-hidden="true" />
              Add a range
            </button>
          </Can>
          <Can permission="MASTER_DATA">
            <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
              <Plus size={16} aria-hidden="true" />
              New container
            </button>
          </Can>
        </div>
      </header>

      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={searchText} onChange={setSearchText} placeholder="Search by LPN…" label="Search containers" />
        </div>
        <label className="select-inline">
          <span>Type</span>
          <select value={query.containerTypeId} onChange={(e) => updateQuery({ containerTypeId: e.target.value })}>
            <option value="">All</option>
            {types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.containerTypeId}
              </option>
            ))}
          </select>
        </label>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as ContainerStatus | '' })}>
            <option value="">All</option>
            {CONTAINER_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} container${page.totalElements === 1 ? '' : 's'}` : ''}
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
              {page.content.map((c) => (
                <tr key={c.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', container: c })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', container: c });
                      }}
                    >
                      {c.lpnId}
                    </button>
                  </th>
                  <td>
                    <span className="code">{c.containerType.containerTypeId}</span>
                    <span className="muted cell__sub">{c.containerType.description}</span>
                  </td>
                  <td>{categoryLabel(c.containerType.category)}</td>
                  <td>
                    <span className={`badge badge--${c.status.toLowerCase().replace('_', '-')}`}>{containerStatusLabel(c.status)}</span>
                  </td>
                  <td className="code">{c.currentLocation?.locationId ?? NONE}</td>
                  <td className="cell--notes">{c.notes ?? NONE}</td>
                  <td className="muted">{formatDate(c.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {page.content.length === 0 && (
            <div className="empty">
              {filtered ? (
                <p>No containers match your search.</p>
              ) : (
                <>
                  <p>No containers yet.</p>
                  <div className="button-row">
                    <Can permission="MASTER_DATA">
                      <button type="button" className="button" onClick={() => setEditing({ mode: 'range' })}>
                        Add a range
                      </button>
                    </Can>
                    <Can permission="MASTER_DATA">
                      <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
                        Create the first container
                      </button>
                    </Can>
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      )}

      {page && page.totalElements > 0 && (
        <Pagination page={page} onPage={(p) => updateQuery({ page: p })} onSize={(s) => updateQuery({ size: s })} />
      )}

      {editing?.mode === 'range' && (
        <ContainerRangeForm
          types={types}
          onClose={() => setEditing(null)}
          onCreated={(r) =>
            done(r.created === 1 ? `Created container ${r.firstLpnId}` : `Created ${r.created.toLocaleString()} containers, ${r.firstLpnId} to ${r.lastLpnId}`)
          }
        />
      )}

      {(editing?.mode === 'new' || editing?.mode === 'edit') && (
        <ContainerForm
          key={editing.mode === 'edit' ? editing.container.id : 'new'}
          container={editing.mode === 'edit' ? editing.container : null}
          types={types}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => done(`${created ? 'Created' : 'Saved'} container ${saved.lpnId}`)}
          onDeleted={(deleted) => done(`Deleted container ${deleted.lpnId}`)}
        />
      )}
    </div>
  );
}

function readQuery(params: URLSearchParams): ContainerQuery {
  const sort = params.get('sort') as ContainerSortField | null;
  const status = params.get('status') as ContainerStatus | null;
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    containerTypeId: params.get('type') ?? '',
    status: status && STATUS_VALUES.includes(status) ? status : '',
    sort: sort && SORT_FIELDS.includes(sort) ? sort : 'lpnId',
    dir: params.get('dir') === 'desc' ? 'desc' : 'asc',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: ContainerQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.containerTypeId) params.set('type', q.containerTypeId);
  if (q.status) params.set('status', q.status);
  if (q.sort !== 'lpnId') params.set('sort', q.sort);
  if (q.dir !== 'asc') params.set('dir', q.dir);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}
