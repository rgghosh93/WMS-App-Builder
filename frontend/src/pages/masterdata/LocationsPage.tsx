import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import {
  formatDimensions,
  listLocationTypes,
  listLocations,
  type Location,
  type LocationQuery,
  type LocationType,
  type LocationTypeInfo,
  type SortField,
} from '../../api/locations';
import { formatVolume, formatWeight } from '../../api/measure';
import { errorMessage, type Page } from '../../api/http';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SortableHeader, type Column } from '../../components/SortableHeader';
import { SearchBox } from '../../components/SearchBox';
import { Can } from '../../session/SessionContext';
import { LocationForm } from './LocationForm';

const COLUMNS: Column<SortField>[] = [
  { field: 'locationId', label: 'Location ID' },
  { field: 'type', label: 'Type' },
  { field: 'area', label: 'Area' },
  { field: 'zone', label: 'Zone' },
  { field: 'aisle', label: 'Aisle' },
  { field: 'bay', label: 'Bay' },
  { field: 'level', label: 'Level' },
  { field: 'position', label: 'Position' },
  { field: null, label: 'Dimensions (W × D × H)' },
  { field: null, label: 'Max weight' },
  { field: null, label: 'Max volume' },
  { field: null, label: 'Max LPNs', numeric: true },
  { field: null, label: 'Max SKUs', numeric: true },
  { field: null, label: 'Max units', numeric: true },
  { field: 'updatedAt', label: 'Updated' },
];

const NO_LIMIT = <span className="muted">—</span>;

function limit(n: number | null) {
  return n == null ? NO_LIMIT : n.toLocaleString();
}

type LoadState =
  | { status: 'loading'; previous?: Page<Location> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<Location> };

/** Editor state: closed, creating a new location, or editing an existing one. */
type Editing = null | { mode: 'new' } | { mode: 'edit'; location: Location };

export function LocationsPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q ?? '');
  const [types, setTypes] = useState<LocationTypeInfo[]>([]);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const updateQuery = useCallback(
    (changes: Partial<LocationQuery>) => {
      setParams(
        (current) => {
          const next = { ...readQuery(current), page: 0, ...changes };
          return writeQuery(next);
        },
        { replace: true },
      );
    },
    [setParams],
  );

  // Debounce typing in the search box into the URL.
  useEffect(() => {
    const handle = setTimeout(() => {
      if ((query.q ?? '') !== searchText.trim()) updateQuery({ q: searchText.trim() });
    }, 250);
    return () => clearTimeout(handle);
  }, [searchText, query.q, updateQuery]);

  useEffect(() => {
    const controller = new AbortController();
    listLocationTypes(controller.signal)
      .then(setTypes)
      .catch(() => {
        /* the list still works; the form falls back to Storage */
      });
    return () => controller.abort();
  }, []);

  const queryKey = params.toString();
  useEffect(() => {
    const controller = new AbortController();
    setState((current) => ({ status: 'loading', previous: current.status === 'ready' ? current.page : current.status === 'loading' ? current.previous : undefined }));
    listLocations(readQuery(new URLSearchParams(queryKey)), controller.signal)
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
  const filtered = Boolean(query.q || query.type);
  const typeLabel = (type: LocationType) => types.find((t) => t.type === type)?.label ?? capitalize(type);
  const formTypes: LocationTypeInfo[] = types.length
    ? types
    : [{ type: 'STORAGE', label: 'Storage', requiredIdentifiers: ['area', 'zone', 'aisle', 'bay', 'level', 'position'] }];

  const toggleSort = (field: SortField) => {
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
        <span aria-current="page">Locations</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Locations</h1>
          <p className="muted">Places where inventory is stored or worked on, with their identifiers and dimensions.</p>
        </div>
        <Can permission="MASTER_DATA">
          <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
            <Plus size={16} aria-hidden="true" />
            New location
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
          <SearchBox
            value={searchText}
            onChange={setSearchText}
            placeholder="Search by ID, area, zone, aisle…"
            label="Search locations"
          />
        </div>
        <label className="select-inline">
          <span>Type</span>
          <select value={query.type ?? ''} onChange={(e) => updateQuery({ type: e.target.value as LocationType | '' })}>
            <option value="">All types</option>
            {formTypes.map((t) => (
              <option key={t.type} value={t.type}>
                {t.label}
              </option>
            ))}
          </select>
        </label>
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} location${page.totalElements === 1 ? '' : 's'}` : ''}
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
              {page.content.map((location) => (
                <tr key={location.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', location })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', location });
                      }}
                    >
                      {location.locationId}
                    </button>
                  </th>
                  <td>{typeLabel(location.type)}</td>
                  <td className="code">{location.area}</td>
                  <td className="code">{location.zone}</td>
                  <td className="code">{location.aisle}</td>
                  <td className="code">{location.bay}</td>
                  <td className="code">{location.level}</td>
                  <td className="code">{location.position}</td>
                  <td className="nowrap">{formatDimensions(location)}</td>
                  <td>{formatWeight(location.maxWeight, location.maxWeightUom) || NO_LIMIT}</td>
                  <td>{formatVolume(location.maxVolume, location.maxVolumeUom) || NO_LIMIT}</td>
                  <td className="num">{limit(location.maxLpns)}</td>
                  <td className="num">{limit(location.maxSkus)}</td>
                  <td className="num">{limit(location.maxUnits)}</td>
                  <td className="nowrap muted">{formatDate(location.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {page.content.length === 0 && (
            <div className="empty">
              {filtered ? (
                <p>No locations match your search.</p>
              ) : (
                <>
                  <p>No locations yet.</p>
                  <Can permission="MASTER_DATA">
                    <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
                      Create the first location
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
        <LocationForm
          key={editing.mode === 'edit' ? editing.location.id : 'new'}
          location={editing.mode === 'edit' ? editing.location : null}
          types={formTypes}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => {
            setEditing(null);
            setNotice(`${created ? 'Created' : 'Saved'} location ${saved.locationId}`);
            setReloadKey((k) => k + 1);
          }}
          onDeleted={(deleted) => {
            setEditing(null);
            setNotice(`Deleted location ${deleted.locationId}`);
            setReloadKey((k) => k + 1);
          }}
        />
      )}
    </div>
  );
}


const SORT_FIELDS: SortField[] = ['locationId', 'type', 'area', 'zone', 'aisle', 'bay', 'level', 'position', 'updatedAt'];

function readQuery(params: URLSearchParams): LocationQuery {
  const sort = params.get('sort') as SortField | null;
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    type: (params.get('type') as LocationType | null) ?? '',
    sort: sort && SORT_FIELDS.includes(sort) ? sort : 'locationId',
    dir: params.get('dir') === 'desc' ? 'desc' : 'asc',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: LocationQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.type) params.set('type', q.type);
  if (q.sort !== 'locationId') params.set('sort', q.sort);
  if (q.dir !== 'asc') params.set('dir', q.dir);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

function capitalize(s: string): string {
  return s.charAt(0) + s.slice(1).toLowerCase();
}
