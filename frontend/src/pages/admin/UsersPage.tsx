import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { ACTIVE_STATUSES, type ActiveStatus } from '../../api/containerTypes';
import { errorMessage, type Page } from '../../api/http';
import { listUsers, type User, type UserQuery, type UserSortField } from '../../api/users';
import { PAGE_SIZES, Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { SortableHeader, type Column } from '../../components/SortableHeader';
import { formatDateTime } from '../outbound/OrdersPage';
import { UserForm } from './UserForm';

const COLUMNS: Column<UserSortField>[] = [
  { field: 'userCode', label: 'User ID' },
  { field: 'displayName', label: 'Name' },
  { field: 'status', label: 'Status' },
  { field: null, label: 'Roles' },
  { field: null, label: 'Signs in to' },
  { field: 'lastSignInAt', label: 'Last sign-in' },
];

const SORT_FIELDS: UserSortField[] = ['userCode', 'displayName', 'status', 'lastSignInAt'];

type LoadState =
  | { status: 'loading'; previous?: Page<User> }
  | { status: 'error'; message: string }
  | { status: 'ready'; page: Page<User> };
type Editing = null | { mode: 'new' } | { mode: 'edit'; id: string };

/** Who can sign in, and with which roles. */
export function UsersPage() {
  const [params, setParams] = useSearchParams();
  const query = readQuery(params);

  const [searchText, setSearchText] = useState(query.q);
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const updateQuery = useCallback(
    (changes: Partial<UserQuery>) => {
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
    listUsers(readQuery(new URLSearchParams(queryKey)), controller.signal)
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

  const done = (message: string) => {
    setEditing(null);
    setNotice(message);
    setReloadKey((k) => k + 1);
  };

  const page = state.status === 'ready' ? state.page : state.status === 'loading' ? state.previous : undefined;
  const filtered = Boolean(query.q || query.status);

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Administration</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Users</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Users</h1>
          <p className="muted">People who sign in: to devices and pack stations with a PIN, to the desktop with a password.</p>
        </div>
        <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
          <Plus size={16} aria-hidden="true" />
          New user
        </button>
      </header>

      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={searchText} onChange={setSearchText} placeholder="Search user ID or name…" label="Search users" />
        </div>
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
          {page ? `${page.totalElements.toLocaleString()} user${page.totalElements === 1 ? '' : 's'}` : ''}
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
          <table className="table" aria-label="Users">
            <SortableHeader
              columns={COLUMNS}
              sort={query.sort}
              dir={query.dir}
              onSort={(field) => updateQuery({ sort: field, dir: query.sort === field && query.dir === 'asc' ? 'desc' : 'asc' })}
            />
            <tbody>
              {page.content.map((u) => (
                <tr key={u.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', id: u.id })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', id: u.id });
                      }}
                    >
                      {u.userCode}
                    </button>
                  </th>
                  <td>{u.displayName}</td>
                  <td>
                    <span className={`badge badge--${u.status.toLowerCase()}`}>{u.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
                    {u.lockedUntil && <span className="muted cell__sub">locked out</span>}
                  </td>
                  <td>{u.roles.length > 0 ? u.roles.map((r) => r.name).join(', ') : <span className="muted">None</span>}</td>
                  <td>
                    {[u.hasPin && 'Devices', u.hasPassword && 'Desktop'].filter(Boolean).join(', ')}
                    {u.devices > 0 && <span className="muted cell__sub">signed in now</span>}
                  </td>
                  <td className="muted">{u.lastSignInAt ? formatDateTime(u.lastSignInAt) : 'Never'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.content.length === 0 && (
            <div className="empty">
              <p>{filtered ? 'No users match your search.' : 'No users yet.'}</p>
            </div>
          )}
        </div>
      )}

      {page && page.totalElements > 0 && (
        <Pagination page={page} onPage={(p) => updateQuery({ page: p })} onSize={(s) => updateQuery({ size: s })} />
      )}

      {editing && (
        <UserForm
          key={editing.mode === 'edit' ? editing.id : 'new'}
          id={editing.mode === 'edit' ? editing.id : null}
          onClose={() => {
            setEditing(null);
            setReloadKey((k) => k + 1);
          }}
          onSaved={(saved, created) => done(`${created ? 'Created' : 'Saved'} user ${saved.userCode}`)}
          onDeleted={(deleted) => done(`Deleted user ${deleted.userCode}`)}
        />
      )}
    </div>
  );
}

function readQuery(params: URLSearchParams): UserQuery {
  const status = params.get('status');
  const sort = params.get('sort') as UserSortField | null;
  const size = Number(params.get('size'));
  const page = Number(params.get('page'));
  return {
    q: params.get('q') ?? '',
    status: status === 'ACTIVE' || status === 'INACTIVE' ? status : '',
    sort: sort && SORT_FIELDS.includes(sort) ? sort : 'userCode',
    dir: params.get('dir') === 'desc' ? 'desc' : 'asc',
    size: PAGE_SIZES.includes(size) ? size : PAGE_SIZES[0],
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

function writeQuery(q: UserQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (q.q) params.set('q', q.q);
  if (q.status) params.set('status', q.status);
  if (q.sort !== 'userCode') params.set('sort', q.sort);
  if (q.dir !== 'asc') params.set('dir', q.dir);
  if (q.size !== PAGE_SIZES[0]) params.set('size', String(q.size));
  if (q.page > 0) params.set('page', String(q.page));
  return params;
}
