import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { errorMessage } from '../../api/http';
import type { Location } from '../../api/locations';
import { ZONE_FUNCTIONS, getZone, listZoneLocations, removeLocations, zoneFunctionLabel, type Zone } from '../../api/zones';
import { Pagination } from '../../components/Pagination';
import { SearchBox } from '../../components/SearchBox';
import { useList } from '../../components/useList';
import { useCan } from '../../session/SessionContext';
import { AddZoneLocationsForm } from './AddZoneLocationsForm';
import { ZoneForm } from './ZoneForm';
import { usedForOrders } from './ZonesPage';

/** One zone and the locations in it. */
export function ZoneDetailPage() {
  const { id } = useParams<{ id: string }>();
  return <ZoneDetail key={id} id={id!} />;
}

function ZoneDetail({ id }: { id: string }) {
  const navigate = useNavigate();
  const may = useCan('MASTER_DATA');
  const [zone, setZone] = useState<Zone | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<null | 'edit' | 'add'>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selected, setSelected] = useState<Map<string, string>>(new Map());
  const [removing, setRemoving] = useState(false);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const list = useList<Location>(useCallback((params, signal) => listZoneLocations(id, params, signal), [id]));

  useEffect(() => {
    const controller = new AbortController();
    getZone(id, controller.signal)
      .then(setZone)
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorMessage(e));
      });
    return () => controller.abort();
  }, [id]);

  useEffect(() => {
    if (!notice) return;
    const handle = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(handle);
  }, [notice]);

  /** The locations changed: show them, and the zone, as they are now. */
  const changed = async (message: string) => {
    setOpen(null);
    setSelected(new Map());
    setNotice(message);
    list.reload();
    setZone(await getZone(id).catch(() => zone));
  };

  const remove = async () => {
    setRemoving(true);
    setRemoveError(null);
    try {
      await removeLocations(id, [...selected.keys()]);
      await changed(`Took ${selected.size} location${selected.size === 1 ? '' : 's'} out`);
    } catch (e) {
      setRemoveError(errorMessage(e));
    } finally {
      setRemoving(false);
    }
  };

  if (error || !zone) {
    return (
      <div className="page">
        {error ? (
          <>
            <div className="notice" role="alert">
              <p>{error}</p>
            </div>
            <p>
              <Link to="/master-data/zones">Back to zones</Link>
            </p>
          </>
        ) : (
          <p className="muted">Loading…</p>
        )}
      </div>
    );
  }

  const rows = list.page?.content ?? [];
  const allSelected = rows.length > 0 && rows.every((l) => selected.has(l.id));
  const toggle = (locations: Location[], on: boolean) =>
    setSelected((current) => {
      const next = new Map(current);
      locations.forEach((l) => (on ? next.set(l.id, l.locationId) : next.delete(l.id)));
      return next;
    });

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/master-data/zones">Zones</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{zone.code}</span>
      </nav>

      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{zone.code}</span>
            <span className={`badge badge--lg badge--${zone.status.toLowerCase()}`}>{zone.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
          </h1>
          <p className="muted">
            {zone.name}. {ZONE_FUNCTIONS.find((f) => f.value === zone.function)?.hint}.
          </p>
        </div>
        <div className="button-row">
          <button type="button" className="button" onClick={() => setOpen('edit')}>
            {may ? 'Edit zone' : 'View zone'}
          </button>
          {may && (
            <button type="button" className="button button--primary" onClick={() => setOpen('add')}>
              <Plus size={16} aria-hidden="true" />
              Add locations
            </button>
          )}
        </div>
      </header>

      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}

      <dl className="facts">
        <div>
          <dt>For</dt>
          <dd>{zoneFunctionLabel(zone.function)}</dd>
        </div>
        <div>
          <dt>Locations</dt>
          <dd>{zone.locationCount.toLocaleString()}</dd>
        </div>
        {zone.function === 'ALLOCATION' && (
          <div>
            <dt>Orders</dt>
            <dd>{usedForOrders(zone)}</dd>
          </div>
        )}
      </dl>

      <div className="toolbar">
        <div className="toolbar__search">
          <SearchBox value={list.searchText} onChange={list.setSearchText} placeholder="Search location ID…" label="Search locations in this zone" />
        </div>
        {may && selected.size > 0 && (
          <div className="button-row">
            <button type="button" className="button" onClick={() => setSelected(new Map())} disabled={removing}>
              Clear selection
            </button>
            <button type="button" className="button button--danger-quiet" onClick={remove} disabled={removing}>
              {removing ? 'Taking out…' : `Take ${selected.size} out of the zone`}
            </button>
          </div>
        )}
      </div>

      {(list.error ?? removeError) && (
        <div className="notice" role="alert">
          <p>{list.error ?? removeError}</p>
          {list.error && (
            <button type="button" className="button" onClick={list.reload}>
              Try again
            </button>
          )}
        </div>
      )}

      {list.page && (
        <div className={`table-wrap${list.loading ? ' table-wrap--loading' : ''}`}>
          {rows.length > 0 ? (
            <table className="table" aria-label="Locations in this zone">
              <thead>
                <tr>
                  {may && (
                    <th scope="col" className="table__select">
                      <input type="checkbox" aria-label="Select all on this page" checked={allSelected} onChange={(e) => toggle(rows, e.target.checked)} />
                    </th>
                  )}
                  <th scope="col">Location</th>
                  <th scope="col">Area</th>
                  <th scope="col">Zone in the address</th>
                  <th scope="col">Aisle</th>
                  <th scope="col">Bay</th>
                  <th scope="col">Level</th>
                  <th scope="col">Position</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((l) => (
                  <tr key={l.id}>
                    {may && (
                      <td className="table__select">
                        <input
                          type="checkbox"
                          aria-label={`Select ${l.locationId}`}
                          checked={selected.has(l.id)}
                          onChange={(e) => toggle([l], e.target.checked)}
                        />
                      </td>
                    )}
                    <th scope="row">
                      <Link className="code" to={`/inventory/by-location?location=${l.id}`} title="What is in it">
                        {l.locationId}
                      </Link>
                    </th>
                    {[l.area, l.zone, l.aisle, l.bay, l.level, l.position].map((v, i) => (
                      <td key={i}>{v ?? <span className="muted">—</span>}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <div className="empty">
              <p>{list.params.get('q') ? 'No locations match your search.' : 'No locations yet, so the zone does nothing.'}</p>
            </div>
          )}
        </div>
      )}
      {list.page && list.page.totalElements > 0 && (
        <Pagination page={list.page} onPage={(p) => list.update({ page: p })} onSize={(s) => list.update({ size: s })} />
      )}

      {open === 'edit' && (
        <ZoneForm
          zone={zone}
          onClose={() => setOpen(null)}
          onSaved={(saved) => {
            setZone(saved);
            setOpen(null);
            setNotice(`Saved ${saved.code}`);
          }}
          onDeleted={() => navigate('/master-data/zones')}
        />
      )}
      {open === 'add' && (
        <AddZoneLocationsForm
          zone={zone}
          onClose={() => setOpen(null)}
          onDone={(added) => void changed(`Added ${added.toLocaleString()} location${added === 1 ? '' : 's'}`)}
        />
      )}
    </div>
  );
}
