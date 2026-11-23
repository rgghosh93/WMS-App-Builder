import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { errorMessage } from '../../api/http';
import { ZONE_FUNCTIONS, listZones, zoneFunctionLabel, type Zone } from '../../api/zones';
import { FilterSelect } from '../../components/ListPage';
import { Can } from '../../session/SessionContext';
import { ZoneForm } from './ZoneForm';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; zones: Zone[] };

/** Groupings of storage locations, each for one function. There are few, so they are shown whole. */
export function ZonesPage() {
  const [params, setParams] = useSearchParams();
  const fn = ZONE_FUNCTIONS.find((f) => f.value === params.get('function'))?.value ?? '';
  const navigate = useNavigate();
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listZones(fn, controller.signal)
      .then((zones) => setState({ status: 'ready', zones }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [fn, reloadKey]);

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Master Data</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Zones</span>
      </nav>

      <header className="page__header">
        <div>
          <h1>Zones</h1>
          <p className="muted">
            Groups of storage locations, each for one function. They are separate from the zone in a location's address, and a location can be in one zone of
            each function.
          </p>
        </div>
        <Can permission="MASTER_DATA">
          <button type="button" className="button button--primary" onClick={() => setCreating(true)}>
            <Plus size={16} aria-hidden="true" />
            New zone
          </button>
        </Can>
      </header>

      <div className="toolbar">
        <FilterSelect label="For" value={fn} options={ZONE_FUNCTIONS} onChange={(value) => setParams(value ? { function: value } : {}, { replace: true })} />
        {fn && <span className="muted">{ZONE_FUNCTIONS.find((f) => f.value === fn)?.hint}.</span>}
        <span className="toolbar__count muted" aria-live="polite">
          {state.status === 'ready' ? `${state.zones.length} zone${state.zones.length === 1 ? '' : 's'}` : ''}
        </span>
      </div>

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
          <table className="table" aria-label="Zones">
            <thead>
              <tr>
                <th scope="col">Zone</th>
                <th scope="col">Name</th>
                <th scope="col">For</th>
                <th scope="col" className="num">
                  Locations
                </th>
                <th scope="col">Orders</th>
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {state.zones.map((z) => (
                <tr key={z.id} className="table__row--link" onClick={() => navigate(`/master-data/zones/${z.id}`)}>
                  <th scope="row">
                    <Link className="code" to={`/master-data/zones/${z.id}`} onClick={(e) => e.stopPropagation()}>
                      {z.code}
                    </Link>
                  </th>
                  <td>{z.name}</td>
                  <td>{zoneFunctionLabel(z.function)}</td>
                  <td className="num">{z.locationCount.toLocaleString()}</td>
                  <td>{z.function === 'ALLOCATION' ? usedForOrders(z) : <span className="muted">—</span>}</td>
                  <td>
                    <span className={`badge badge--${z.status.toLowerCase()}`}>{z.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {state.zones.length === 0 && (
            <div className="empty">
              <p>{fn ? `No ${zoneFunctionLabel(fn).toLowerCase()} zones yet.` : 'No zones yet.'}</p>
            </div>
          )}
        </div>
      )}

      {creating && (
        <ZoneForm
          zone={null}
          initialFunction={fn || 'ALLOCATION'}
          onClose={() => setCreating(false)}
          onSaved={(saved) => navigate(`/master-data/zones/${saved.id}`)}
        />
      )}
    </div>
  );
}

/** How an allocation zone's stock is used for orders. */
export function usedForOrders(zone: Zone): string {
  if (!zone.allocatable) return 'Kept from orders';
  return zone.priority == null ? 'Used last' : `Priority ${zone.priority}`;
}
