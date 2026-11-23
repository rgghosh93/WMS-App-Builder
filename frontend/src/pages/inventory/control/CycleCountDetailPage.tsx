import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { countStatusLabel, decideCount, getCount, type CountDetail } from '../../../api/control';
import { errorMessage } from '../../../api/http';
import { useCan, useSession } from '../../../session/SessionContext';
import { formatDateTime } from '../../outbound/OrdersPage';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; count: CountDetail };
type Decision = 'approve' | 'recount' | 'cancel';

/** One count: what was found against what was expected, and what to do about it. */
export function CycleCountDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const may = useCan('INVENTORY');
  const mayApprove = useCan('INVENTORY_APPROVE');
  const me = useSession()?.user.id;

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    getCount(id, controller.signal)
      .then((count) => setState({ status: 'ready', count }))
      .catch((e) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(e) });
      });
    return () => controller.abort();
  }, [id]);

  if (state.status === 'loading') return <p className="muted page">Loading…</p>;
  if (state.status === 'error') {
    return (
      <div className="page">
        <div className="notice" role="alert">
          <p>{state.message}</p>
        </div>
        <p>
          <Link to="/inventory/counts">Back to cycle counts</Link>
        </p>
      </div>
    );
  }

  const { task, results } = state.count;
  const reviewing = task.status === 'REVIEW';
  const active = !['COMPLETED', 'CANCELLED'].includes(task.status);
  const mine = task.counterId === me;

  const decide = async (what: Decision) => {
    setBusy(true);
    setError(null);
    try {
      setState({ status: 'ready', count: await decideCount(task.id, what) });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/inventory/counts">Cycle Counts</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{task.number}</span>
      </nav>
      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{task.number}</span>
            <span className={`badge badge--lg badge--count-${task.status.toLowerCase()}`}>{countStatusLabel(task.status)}</span>
          </h1>
          <p className="muted">
            {['COUNTING', 'REVIEW', 'RECOUNT'].includes(task.status) ? 'Nothing can be moved or picked here until this is settled.' : `Counts ${task.location}.`}
          </p>
        </div>
        <div className="button-row">
          {may && active && (
            <button type="button" className="button button--danger-quiet" disabled={busy} onClick={() => void decide('cancel')}>
              Cancel count
            </button>
          )}
          {may && reviewing && (
            <button type="button" className="button" disabled={busy} onClick={() => void decide('recount')}>
              Count again
            </button>
          )}
          {mayApprove && reviewing && !mine && (
            <button type="button" className="button button--primary" disabled={busy} onClick={() => void decide('approve')}>
              Approve differences
            </button>
          )}
        </div>
      </header>
      {error && (
        <div className="form__error" role="alert">
          {error}
        </div>
      )}
      {reviewing && mine && <p className="notice-info">Someone else has to approve what you counted.</p>}

      <dl className="facts">
        <div>
          <dt>Location</dt>
          <dd>
            <Link className="code" to={`/inventory/by-location?location=${task.locationId}`}>
              {task.location}
            </Link>
          </dd>
        </div>
        <div>
          <dt>Planned</dt>
          <dd>
            {formatDateTime(task.plannedAt)}
            <span className="muted cell__sub">by {task.plannedBy}</span>
          </dd>
        </div>
        <div>
          <dt>Counted</dt>
          <dd>
            {task.countedAt ? formatDateTime(task.countedAt) : <span className="muted">Not yet</span>}
            {task.counter && <span className="muted cell__sub">by {task.counter}</span>}
          </dd>
        </div>
        <div>
          <dt>Times counted</dt>
          <dd>{task.rounds}</dd>
        </div>
        {task.decider && (
          <div>
            <dt>Decided</dt>
            <dd>
              {formatDateTime(task.decidedAt!)}
              <span className="muted cell__sub">by {task.decider}</span>
            </dd>
          </div>
        )}
      </dl>

      {task.countedAt && (
        <section className="section" aria-labelledby="count-results">
          <h2 id="count-results">Found</h2>
          {results.length === 0 ? (
            <p className="muted">Nothing expected and nothing found.</p>
          ) : (
            <div className="table-wrap">
              <table className="table" aria-label="Found">
                <thead>
                  <tr>
                    <th scope="col">Item</th>
                    <th scope="col">LPN</th>
                    <th scope="col">Lot / serial</th>
                    <th scope="col" className="num">
                      Expected
                    </th>
                    <th scope="col" className="num">
                      Counted
                    </th>
                    <th scope="col" className="num">
                      Difference
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {results.map((r, i) => (
                    <tr key={i}>
                      <td>
                        <span className="code">{r.item.itemId}</span>
                        <span className="muted cell__sub">{r.item.description}</span>
                      </td>
                      <td className="code">{r.lpn ?? <span className="muted">Loose</span>}</td>
                      <td className="code">{[r.lotNumber, r.serialNumber].filter(Boolean).join(' / ') || <span className="muted">—</span>}</td>
                      <td className="num">{r.expected.toLocaleString()} EA</td>
                      <td className="num">{r.counted.toLocaleString()} EA</td>
                      <td className="num">
                        {r.difference === 0 ? <span className="muted">—</span> : <strong>{r.difference > 0 ? `+${r.difference.toLocaleString()}` : `−${(-r.difference).toLocaleString()}`}</strong>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}
    </div>
  );
}
