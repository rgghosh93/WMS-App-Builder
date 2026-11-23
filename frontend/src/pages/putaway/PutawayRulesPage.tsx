import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { categoryLabel } from '../../api/containerTypes';
import { errorMessage } from '../../api/http';
import { inventoryStatusLabel } from '../../api/inventory';
import { listPutawayRules, type PutawayRule } from '../../api/putaway';
import { Can } from '../../session/SessionContext';
import { PutawayRuleForm } from './PutawayRuleForm';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; rules: PutawayRule[] };
type Editing = null | { mode: 'new' } | { mode: 'edit'; rule: PutawayRule };

/** How the system chooses storage locations. Rules are few, so they are shown whole, in the order they are tried. */
export function PutawayRulesPage() {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listPutawayRules(controller.signal)
      .then((rules) => setState({ status: 'ready', rules }))
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
        <span>Put-away</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Put-away Rules</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Put-away Rules</h1>
          <p className="muted">Tried from the top. The first rule that matches an LPN and finds room for it decides where it goes.</p>
        </div>
        <Can permission="PUTAWAY_RULES">
          <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
            <Plus size={16} aria-hidden="true" />
            New rule
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
          <table className="table" aria-label="Put-away rules">
            <thead>
              <tr>
                <th scope="col" className="num">
                  Order
                </th>
                <th scope="col">Rule</th>
                <th scope="col">For</th>
                <th scope="col">Goes to</th>
                <th scope="col">LPN</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {state.rules.map((rule) => (
                <tr key={rule.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', rule })}>
                  <td className="num">{rule.sequence}</td>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button"
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', rule });
                      }}
                    >
                      {rule.name}
                    </button>
                  </th>
                  <td>{matches(rule)}</td>
                  <td>{destination(rule)}</td>
                  <td>{rule.handling === 'KEEP_LPN' ? 'Kept' : 'Emptied'}</td>
                  <td>
                    <span className={`badge badge--${rule.status.toLowerCase()}`}>{rule.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {state.rules.length === 0 && (
            <div className="empty">
              <p>No rules yet, so people choose the location themselves.</p>
            </div>
          )}
        </div>
      )}

      {editing && (
        <PutawayRuleForm
          key={editing.mode === 'edit' ? editing.rule.id : 'new'}
          rule={editing.mode === 'edit' ? editing.rule : null}
          nextSequence={state.status === 'ready' ? Math.max(0, ...state.rules.map((r) => r.sequence)) + 10 : 10}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => done(`${created ? 'Created' : 'Saved'} rule ${saved.name}`)}
          onDeleted={(deleted) => done(`Deleted rule ${deleted.name}`)}
        />
      )}
    </div>
  );
}

function matches(rule: PutawayRule): string {
  const parts = [
    rule.item && `only ${rule.item.itemId}`,
    rule.containerCategory && `${categoryLabel(rule.containerCategory)} LPNs`,
    rule.stockStatus && `${inventoryStatusLabel(rule.stockStatus)} stock`,
  ].filter(Boolean);
  return parts.length === 0 ? 'Everything' : parts.join(', ');
}

function destination(rule: PutawayRule): string {
  if (rule.strategy === 'FIXED') return rule.location?.locationId ?? '';
  const where = rule.zone ? `zone ${rule.zone.code}` : '';
  return rule.strategy === 'ZONE' ? `Z${where.slice(1)}` : `Nearest empty location${where ? ` in ${where}` : ''}`;
}
