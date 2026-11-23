import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, RefreshCw } from 'lucide-react';
import { errorMessage } from '../../api/http';
import { listReplenishmentRules, runReplenishment, type ReplenishmentRule } from '../../api/replenishment';
import { Can } from '../../session/SessionContext';
import { ReplenishmentRuleForm } from './ReplenishmentRuleForm';
import { ran } from './ReplenishmentTasksPage';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; rules: ReplenishmentRule[] };
type Editing = null | { mode: 'new' } | { mode: 'edit'; rule: ReplenishmentRule };

/** Which items are kept stocked in which pick locations, and how those stand now. */
export function ReplenishmentRulesPage() {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listReplenishmentRules(controller.signal)
      .then((rules) => setState({ status: 'ready', rules }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [reloadKey]);

  useEffect(() => {
    if (!notice) return;
    const handle = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(handle);
  }, [notice]);

  const done = (message: string) => {
    setEditing(null);
    setNotice(message);
    setReloadKey((k) => k + 1);
  };

  const run = async () => {
    setRunning(true);
    setProblem(null);
    try {
      done(ran(await runReplenishment()));
    } catch (error) {
      setProblem(errorMessage(error));
    } finally {
      setRunning(false);
    }
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Replenishment</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Replenishment Rules</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Replenishment Rules</h1>
          <p className="muted">
            When what is free in a pick location falls below its minimum, it is filled to its maximum from other storage, earliest expiry first. Whether orders may
            take stock from elsewhere is up to the <Link to="/master-data/zones?function=ALLOCATION">allocation zones</Link>.
          </p>
        </div>
        <Can permission="REPLENISHMENT">
          <div className="button-row">
            <button type="button" className="button" disabled={running} onClick={() => void run()}>
              <RefreshCw size={16} aria-hidden="true" />
              Run now
            </button>
            <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
              <Plus size={16} aria-hidden="true" />
              New rule
            </button>
          </div>
        </Can>
      </header>

      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
      {problem && (
        <div className="notice" role="alert">
          <p>{problem}</p>
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
          <table className="table" aria-label="Replenishment rules">
            <thead>
              <tr>
                <th scope="col">Pick location</th>
                <th scope="col">Item</th>
                {['Minimum', 'Maximum', 'On hand', 'Reserved', 'Coming', 'Orders short'].map((h) => (
                  <th key={h} scope="col" className="num">
                    {h}
                  </th>
                ))}
                <th scope="col">From</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {state.rules.map((rule) => (
                <tr key={rule.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', rule })}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      aria-label={`${rule.item.itemId} at ${rule.location.locationId}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        setEditing({ mode: 'edit', rule });
                      }}
                    >
                      {rule.location.locationId}
                    </button>
                  </th>
                  <td>
                    <span className="code">{rule.item.itemId}</span>
                    <span className="muted cell__sub">{rule.item.description}</span>
                  </td>
                  <td className="num">{rule.minQuantity.toLocaleString()}</td>
                  <td className="num">
                    {rule.maxQuantity.toLocaleString()}
                    {rule.multiple > 1 && <span className="muted cell__sub">by {rule.multiple.toLocaleString()}</span>}
                  </td>
                  {[rule.onHand, rule.reserved, rule.coming, rule.forOrders ? rule.waiting : null].map((n, i) => (
                    <td key={i} className="num">
                      {n ? n.toLocaleString() : <span className="muted">{n === 0 ? '0' : '—'}</span>}
                    </td>
                  ))}
                  <td>{rule.sourceZone ? <span className="code">{rule.sourceZone.code}</span> : 'Anywhere'}</td>
                  <td>{standing(rule)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {state.rules.length === 0 && (
            <div className="empty">
              <p>No rules yet, so nothing is replenished.</p>
            </div>
          )}
        </div>
      )}

      {editing && (
        <ReplenishmentRuleForm
          key={editing.mode === 'edit' ? editing.rule.id : 'new'}
          rule={editing.mode === 'edit' ? editing.rule : null}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => done(`${created ? 'Created' : 'Saved'} the rule for ${saved.item.itemId} at ${saved.location.locationId}`)}
          onDeleted={(deleted) => done(`Deleted the rule for ${deleted.item.itemId} at ${deleted.location.locationId}`)}
        />
      )}
    </div>
  );
}

function standing(rule: ReplenishmentRule) {
  if (rule.status === 'INACTIVE') return <span className="badge badge--inactive">Inactive</span>;
  if (rule.needed) return <span className="badge badge--task-short">{rule.needed.toLocaleString()} EA needed</span>;
  return rule.coming ? <span className="badge badge--task-in-progress">Coming</span> : <span className="badge badge--active">Stocked</span>;
}
