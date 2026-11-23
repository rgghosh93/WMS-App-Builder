import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { ASN_STATUSES, asnStatusLabel, listAsns, type AsnQuery, type AsnSortField, type AsnStatus, type AsnSummary } from '../../api/asns';
import { errorMessage, type Page } from '../../api/http';
import { statusClass } from '../../api/orders';
import { useOwners } from '../../api/owners';
import { OwnerFilter } from '../../components/OwnerSelect';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { SortableHeader, type Column } from '../../components/SortableHeader';
import { Can } from '../../session/SessionContext';
import { AllocationBar, formatDateTime, formatDay } from '../outbound/OrdersPage';
import { NewAsnForm } from './NewAsnForm';

const COLUMNS: Column<AsnSortField>[] = [
  { field: 'asnNumber', label: 'ASN' },
  { field: 'vendorName', label: 'Vendor' },
  { field: null, label: 'Reference' },
  { field: 'status', label: 'Status' },
  { field: null, label: 'Lines', numeric: true },
  { field: null, label: 'Received' },
  { field: 'expectedDate', label: 'Expected' },
  { field: 'createdAt', label: 'Created' },
];

const SORT_FIELDS: AsnSortField[] = ['createdAt', 'asnNumber', 'vendorName', 'expectedDate', 'status'];

type LoadState =
  | { status: 'loading'; previous?: Page<AsnSummary> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<AsnSummary> };

/** What vendors say is coming, newest first. */
export function AsnsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [creating, setCreating] = useState(false);
  const { several } = useOwners();

  const updateQuery = useCallback(
    (changes: Partial<AsnQuery>) => {
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
    listAsns(readQuery(new URLSearchParams(queryKey)), controller.signal)
      .then((page) => setState({ status: 'ready', page }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [queryKey, reloadKey]);

  const page = state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined;
  const filtered = Boolean(query.q || query.status || query.owner);

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Inbound</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">ASNs</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>ASNs</h1>
          <p className="muted">Advance shipping notices: what vendors say is coming. They are received on a mobile device, where things can be received without one too.</p>
        </div>
        <Can permission="INBOUND">
          <button type="button" className="button button--primary" onClick={() => setCreating(true)}>
            <Plus size={16} aria-hidden="true" />
            New ASN
          </button>
        </Can>
      </header>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={searchText} onChange={setSearchText} placeholder="Search ASN, vendor, reference, item or LPN…" label="Search ASNs" />
        </div>
        <label className="select-inline">
          <span>Status</span>
          <select value={query.status} onChange={(e) => updateQuery({ status: e.target.value as AsnStatus | '' })}>
            <option value="">All</option>
            {ASN_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
        <OwnerFilter value={query.owner ?? ''} onChange={(owner) => updateQuery({ owner })} />
        <span className="toolbar__count muted" aria-live="polite">
          {page ? `${page.totalElements.toLocaleString()} ASN${page.totalElements === 1 ? '' : 's'}` : ''}
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
          <table className="table" aria-label="ASNs">
            <SortableHeader
              columns={COLUMNS}
              sort={query.sort}
              dir={query.dir}
              onSort={(field) => updateQuery({ sort: field, dir: query.sort === field && query.dir === 'asc' ? 'desc' : 'asc' })}
            />
            <tbody>
              {page.content.map((a) => (
                <tr key={a.id} className="table__row--link" onClick={() => navigate(`/inbound/asns/${a.id}`)}>
                  <th scope="row">
                    <Link className="code" to={`/inbound/asns/${a.id}`} onClick={(e) => e.stopPropagation()}>
                      {a.asnNumber}
                    </Link>
                    {(a.blind || (several && a.owner)) && (
                      <span className="muted cell__sub">{[several && a.owner?.code, a.blind && 'No ASN'].filter(Boolean).join(' · ')}</span>
                    )}
                  </th>
                  <td>{a.vendorName}</td>
                  <td className="code">{a.reference ?? <span className="muted">—</span>}</td>
                  <td>
                    <span className={`badge badge--asn-${statusClass(a.status)}`}>{asnStatusLabel(a.status)}</span>
                  </td>
                  <td className="num">{a.lineCount}</td>
                  <td>
                    {a.blind ? (
                      `${a.receivedQuantity.toLocaleString()} EA`
                    ) : (
                      <AllocationBar allocated={a.receivedQuantity} ordered={a.expectedQuantity} word="received" />
                    )}
                  </td>
                  <td>{a.expectedDate ? formatDay(a.expectedDate) : <span className="muted">—</span>}</td>
                  <td className="muted">{formatDateTime(a.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.content.length === 0 && (
            <div className="empty">
              <p>{filtered ? 'No ASNs match your search.' : 'No ASNs yet. Add the first one with New ASN.'}</p>
            </div>
          )}
        </div>
      )}

      {page && page.totalElements > 0 && (
        <Pagination page={page} onPage={(p) => updateQuery({ page: p })} onSize={(s) => updateQuery({ size: s })} />
      )}

      {creating && <NewAsnForm onClose={() => setCreating(false)} onCreated={(asn) => navigate(`/inbound/asns/${asn.id}`)} />}
    </div>
  );
}

function readQuery(params: URLSearchParams): AsnQuery {
  const status = params.get('status');
  const sort = params.get('sort') as AsnSortField | null;
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    owner: params.get('owner') ?? '',
    status: ASN_STATUSES.find((s) => s.value === status)?.value ?? '',
    sort: sort && SORT_FIELDS.includes(sort) ? sort : 'createdAt',
    dir: params.get('dir') === 'asc' ? 'asc' : 'desc',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: AsnQuery): URLSearchParams {
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
