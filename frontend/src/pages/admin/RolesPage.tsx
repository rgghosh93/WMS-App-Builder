import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { errorMessage } from '../../api/http';
import { listPermissions, listRoles, type PermissionInfo, type Role } from '../../api/users';
import { RoleForm } from './RoleForm';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; roles: Role[]; permissions: PermissionInfo[] };
type Editing = null | { mode: 'new' } | { mode: 'edit'; role: Role };

/** Roles and what they let people do. Roles are few, so they are shown whole. */
export function RolesPage() {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([listRoles(controller.signal), listPermissions(controller.signal)])
      .then(([roles, permissions]) => setState({ status: 'ready', roles, permissions }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [reloadKey]);

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

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Administration</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Roles</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Roles</h1>
          <p className="muted">What people may do. Someone with several roles may do whatever any of them allows.</p>
        </div>
        <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
          <Plus size={16} aria-hidden="true" />
          New role
        </button>
      </header>

      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
      {state.status === 'loading' && <p className="muted">Loading…</p>}
      {state.status === 'error' && (
        <div className="notice" role="alert">
          <p>{state.message}</p>
          <button type="button" className="button" onClick={() => setReloadKey((k) => k + 1)}>
            Try again
          </button>
        </div>
      )}

      {state.status === 'ready' && (
        <div className="table-wrap">
          <table className="table" aria-label="Roles">
            <thead>
              <tr>
                <th scope="col">Role</th>
                <th scope="col">Desktop</th>
                <th scope="col">Devices</th>
                <th scope="col" className="num">
                  Users
                </th>
              </tr>
            </thead>
            <tbody>
              {state.roles.map((role) => (
                <tr key={role.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', role })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', role });
                      }}
                    >
                      {role.name}
                    </button>
                    {role.description && <span className="muted cell__sub">{role.description}</span>}
                  </th>
                  <td className="cell--wrap">
                    <Allowed role={role} group="DESKTOP" permissions={state.permissions} />
                  </td>
                  <td className="cell--wrap">
                    <Allowed role={role} group="DEVICE" permissions={state.permissions} />
                  </td>
                  <td className="num">{role.userCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {editing && state.status === 'ready' && (
        <RoleForm
          key={editing.mode === 'edit' ? editing.role.id : 'new'}
          role={editing.mode === 'edit' ? editing.role : null}
          permissions={state.permissions}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => done(`${created ? 'Created' : 'Saved'} role ${saved.name}`)}
          onDeleted={(deleted) => done(`Deleted role ${deleted.name}`)}
        />
      )}
    </div>
  );
}

function Allowed({ role, group, permissions }: { role: Role; group: PermissionInfo['group']; permissions: PermissionInfo[] }) {
  const all = permissions.filter((p) => p.group === group);
  const held = all.filter((p) => role.permissions.includes(p.id));
  if (held.length === 0) return <span className="muted">—</span>;
  if (held.length === all.length) return <>Everything</>;
  return <>{held.map((p) => p.label).join(', ')}</>;
}
