import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { carrierModeLabel, listCarriers, type Carrier } from '../../api/carriers';
import { errorMessage } from '../../api/http';
import { Can } from '../../session/SessionContext';
import { CarrierForm } from './CarrierForm';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; carriers: Carrier[] };
type Editing = null | { mode: 'new' } | { mode: 'edit'; carrier: Carrier };

/** Carriers and their services. The list is short, so it is shown whole. */
export function CarriersPage() {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listCarriers(controller.signal)
      .then((carriers) => setState({ status: 'ready', carriers }))
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
        <span>Master Data</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Carriers</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Carriers</h1>
          <p className="muted">Companies that take shipments away, and the services they offer.</p>
        </div>
        <Can permission="MASTER_DATA">
          <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
            <Plus size={16} aria-hidden="true" />
            New carrier
          </button>
        </Can>
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
          <table className="table" aria-label="Carriers">
            <thead>
              <tr>
                <th scope="col">Code</th>
                <th scope="col">Name</th>
                <th scope="col">Mode</th>
                <th scope="col">Services</th>
                <th scope="col">Tracking</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {state.carriers.map((c) => (
                <tr key={c.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', carrier: c })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', carrier: c });
                      }}
                    >
                      {c.carrierCode}
                    </button>
                  </th>
                  <td>{c.name}</td>
                  <td>{carrierModeLabel(c.mode)}</td>
                  <td>
                    {c.services.map((s) => (
                      <span key={s.id} className={s.status === 'ACTIVE' ? 'tag' : 'tag muted'}>
                        {s.name}
                        {s.status === 'INACTIVE' ? ' (inactive)' : ''}
                      </span>
                    ))}
                  </td>
                  <td>{c.trackingRequired ? 'Required' : 'Optional'}</td>
                  <td>
                    <span className={`badge badge--${c.status.toLowerCase()}`}>{c.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {state.carriers.length === 0 && (
            <div className="empty">
              <p>No carriers yet.</p>
            </div>
          )}
        </div>
      )}

      {editing && (
        <CarrierForm
          key={editing.mode === 'edit' ? editing.carrier.id : 'new'}
          carrier={editing.mode === 'edit' ? editing.carrier : null}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => done(`${created ? 'Created' : 'Saved'} carrier ${saved.carrierCode}`)}
          onDeleted={(deleted) => done(`Deleted carrier ${deleted.carrierCode}`)}
        />
      )}
    </div>
  );
}
