import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { listUoms, uomKindLabel, type Uom } from '../../api/uoms';
import { errorMessage } from '../../api/http';
import { Can } from '../../session/SessionContext';
import { UomForm } from './UomForm';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; uoms: Uom[] };
type Editing = null | { mode: 'new' } | { mode: 'edit'; uom: Uom };

/** Units of measure. The list is short, so it is shown whole without paging or search. */
export function UomsPage() {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listUoms(controller.signal)
      .then((uoms) => setState({ status: 'ready', uoms }))
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
        <span aria-current="page">Units of Measure</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Units of Measure</h1>
          <p className="muted">
            The units stock is handled in. How many eaches each unit holds is set per item, under the item's packaging.
          </p>
        </div>
        <Can permission="MASTER_DATA">
          <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
            <Plus size={16} aria-hidden="true" />
            New unit
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
        <div className="table-wrap table-wrap--narrow">
          <table className="table">
            <thead>
              <tr>
                <th scope="col">Code</th>
                <th scope="col">Description</th>
                <th scope="col">Packaging level</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {state.uoms.map((uom) => (
                <tr key={uom.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', uom })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', uom });
                      }}
                    >
                      {uom.uomCode}
                    </button>
                    {uom.base && <span className="tag tag--accent">Base unit</span>}
                  </th>
                  <td>{uom.description}</td>
                  <td>{uomKindLabel(uom.kind)}</td>
                  <td>
                    <span className={`badge badge--${uom.status.toLowerCase()}`}>{uom.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {editing && (
        <UomForm
          key={editing.mode === 'edit' ? editing.uom.id : 'new'}
          uom={editing.mode === 'edit' ? editing.uom : null}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => done(`${created ? 'Created' : 'Saved'} unit ${saved.uomCode}`)}
          onDeleted={(deleted) => done(`Deleted unit ${deleted.uomCode}`)}
        />
      )}
    </div>
  );
}
