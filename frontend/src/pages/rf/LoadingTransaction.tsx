import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import type { Carrier } from '../../api/carriers';
import {
  closeManifest,
  discardManifest,
  getDoor,
  listDoors,
  listLoadingCarriers,
  loadCarton,
  openManifest,
  unloadCarton,
  type DockDoor,
  type DoorState,
  type Manifest,
} from '../../api/shipping';
import { ScanInput } from './ScanInput';

/** Loading: choose a dock door and carrier, scan cartons on, then close the manifest to ship them. */
export function LoadingTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const [doors, setDoors] = useState<DockDoor[] | null>(null);
  const [state, setState] = useState<DoorState | null>(null);
  const [carriers, setCarriers] = useState<Carrier[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [mode, setMode] = useState<'load' | 'unload' | 'close'>('load');
  /** Cartons not loaded, waiting for the loader to say whether to leave them behind. */
  const [leftBehind, setLeftBehind] = useState<string | null>(null);
  const [trailer, setTrailer] = useState('');
  const [seal, setSeal] = useState('');

  const fail = useCallback(
    (e: unknown) => {
      if (e instanceof ApiError && e.status === 401) onSignedOut();
      else setError(errorMessage(e));
    },
    [onSignedOut],
  );

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([listDoors(controller.signal), listLoadingCarriers(controller.signal)])
      .then(([d, c]) => {
        setDoors(d);
        setCarriers(c.filter((carrier) => carrier.status === 'ACTIVE'));
      })
      .catch((e) => {
        if (!controller.signal.aborted) fail(e);
      });
    return () => controller.abort();
  }, [fail]);

  /** Runs one action; true if it worked. */
  const run = async (action: () => Promise<DoorState>, message: ((next: DoorState) => string) | null = null): Promise<boolean> => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const next = await action();
      setState(next);
      setDone(message ? message(next) : null);
      return true;
    } catch (e) {
      fail(e);
      // The manifest was closed or changed elsewhere: show where things stand now.
      if (e instanceof ApiError && e.status === 409 && state) {
        try {
          setState(await getDoor(state.door.locationId));
        } catch {
          // Keep what we had; the message says what went wrong.
        }
      }
      return false;
    } finally {
      setBusy(false);
    }
  };

  const close = async (manifest: Manifest, leaveBehind: boolean) => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const closed = await closeManifest(manifest.id, { trailerNumber: trailer.trim(), sealNumber: seal.trim(), leaveBehind });
      const n = closed.cartonCount;
      setLeftBehind(null);
      setMode('load');
      setTrailer('');
      setSeal('');
      setDone(`${closed.manifestNumber} is closed: ${n} carton${n === 1 ? '' : 's'} shipped with ${closed.carrier.name}.`);
      setState(null);
      setDoors(await listDoors());
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.fieldErrors.cartons) setLeftBehind(e.fieldErrors.cartons);
      else fail(e);
    } finally {
      setBusy(false);
    }
  };

  const leaveDoor = async () => {
    setState(null);
    setMode('load');
    setLeftBehind(null);
    setError(null);
    setDone(null);
    try {
      setDoors(await listDoors());
    } catch (e) {
      fail(e);
    }
  };

  // ---------- Which door ----------

  if (!state) {
    return (
      <>
        <h1 className="rf-title">Loading</h1>
        {done && (
          <p className="rf-done" role="status">
            {done}
          </p>
        )}
        <section className="rf-card">
          <p className="rf-card__action">Scan a dock door</p>
        </section>
        <ScanInput label="Dock door" busy={busy} error={error} onScan={(code) => run(() => getDoor(code))} />
        {doors?.length === 0 && <p className="muted">No dock doors are set up. Add a location of type Dock door.</p>}
        <div className="rf-choices">
          {doors?.map((d) => (
            <button key={d.id} type="button" className="rf-button" disabled={busy} onClick={() => run(() => getDoor(d.locationId))}>
              <span className="code">{d.locationId}</span>
              {d.manifestNumber && ` · ${d.manifestNumber}, ${d.carrierName}`}
            </button>
          ))}
        </div>
      </>
    );
  }

  const manifest = state.manifest;

  // ---------- Which carrier ----------

  if (!manifest) {
    return (
      <>
        <h1 className="rf-title code">{state.door.locationId}</h1>
        {done && (
          <p className="rf-done" role="status">
            {done}
          </p>
        )}
        <section className="rf-card">
          <p className="rf-card__action">Which carrier is at this door?</p>
        </section>
        {error && (
          <p className="rf-error" role="alert">
            {error}
          </p>
        )}
        <div className="rf-choices">
          {carriers.map((c) => (
            <button key={c.id} type="button" className="rf-button" disabled={busy} onClick={() => run(() => openManifest(state.door.locationId, c.id))}>
              {c.name}
            </button>
          ))}
        </div>
        {carriers.length === 0 && <p className="muted">No carriers are set up yet.</p>}
        <button type="button" className="rf-button rf-button--quiet" onClick={() => void leaveDoor()}>
          Different door
        </button>
      </>
    );
  }

  // ---------- Loading ----------

  const toLoad = [
    ...manifest.shipments.flatMap((s) => s.cartons.filter((c) => !c.loadedAt).map((c) => ({ carton: c, shipment: s }))),
    ...state.waiting.flatMap((s) => s.cartons.map((c) => ({ carton: c, shipment: s }))),
  ];
  const loaded = manifest.shipments.flatMap((s) => s.cartons.filter((c) => c.loadedAt).map((c) => ({ carton: c, shipment: s })));

  return (
    <>
      <section className="rf-strip" aria-label="Manifest">
        <div className="rf-strip__row">
          <h1 className="code">{manifest.manifestNumber}</h1>
          <span>{manifest.carrier.name}</span>
          <span className="rf-strip__into">
            at <span className="code">{state.door.locationId}</span>
          </span>
        </div>
        <span className="muted">
          {loaded.length} carton{loaded.length === 1 ? '' : 's'} loaded · {toLoad.length} to load
        </span>
      </section>
      {done && (
        <p className="rf-done" role="status">
          {done}
        </p>
      )}

      {mode === 'close' ? (
        leftBehind ? (
          <section className="rf-card" role="group" aria-label="Not everything is loaded">
            <p className="rf-card__action">Not loaded: {leftBehind}</p>
            <p className="muted">Ship without them? They move to a shipment of their own.</p>
            {error && (
              <p className="rf-error" role="alert">
                {error}
              </p>
            )}
            <div className="rf-choices">
              <button type="button" className="rf-button rf-button--danger" disabled={busy} onClick={() => close(manifest, true)}>
                Ship without them
              </button>
              <button
                type="button"
                className="rf-button rf-button--quiet"
                onClick={() => {
                  setLeftBehind(null);
                  setMode('load');
                }}
              >
                Keep loading
              </button>
            </div>
          </section>
        ) : (
          <form
            className="rf-card"
            aria-label="Close manifest"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              void close(manifest, false);
            }}
          >
            <p className="rf-card__action">Ship {loaded.length} carton{loaded.length === 1 ? '' : 's'}?</p>
            <p className="muted">This can't be undone: the stock leaves inventory.</p>
            <label className="scan__label" htmlFor="rf-trailer">
              Trailer (optional)
            </label>
            <input id="rf-trailer" className="scan__input" value={trailer} maxLength={40} autoComplete="off" onChange={(e) => setTrailer(e.target.value)} />
            <label className="scan__label" htmlFor="rf-seal">
              Seal (optional)
            </label>
            <input id="rf-seal" className="scan__input" value={seal} maxLength={40} autoComplete="off" onChange={(e) => setSeal(e.target.value)} />
            {error && (
              <p className="rf-error" role="alert">
                {error}
              </p>
            )}
            <button type="submit" className="rf-button rf-button--primary" disabled={busy}>
              {busy ? 'Shipping…' : 'Close manifest and ship'}
            </button>
            <button
              type="button"
              className="rf-button rf-button--quiet"
              disabled={busy}
              onClick={() => {
                setError(null);
                setMode('load');
              }}
            >
              Keep loading
            </button>
          </form>
        )
      ) : (
        <>
          <ScanInput
            key={mode}
            label={mode === 'load' ? 'Scan a carton to load' : 'Scan a carton to take off'}
            busy={busy}
            error={error}
            onScan={(lpn) =>
              run(
                () => (mode === 'load' ? loadCarton(manifest.id, lpn) : unloadCarton(manifest.id, lpn)),
                () => `${lpn.toUpperCase()} ${mode === 'load' ? 'loaded' : 'taken off'}.`,
              ).then((ok) => ok && setMode('load'))
            }
          />
          {loaded.length > 0 && mode === 'load' && (
            <button
              type="button"
              className="rf-button rf-button--primary"
              disabled={busy}
              onClick={() => {
                setError(null);
                setDone(null);
                setMode('close');
              }}
            >
              Close manifest
            </button>
          )}
          <CartonList title="To load" rows={toLoad} />
          <CartonList title="Loaded" rows={loaded} />
          {mode === 'unload' ? (
            <button type="button" className="rf-button rf-button--quiet" onClick={() => setMode('load')}>
              Back to loading
            </button>
          ) : (
            loaded.length > 0 && (
              <button
                type="button"
                className="rf-button rf-button--quiet"
                onClick={() => {
                  setError(null);
                  setMode('unload');
                }}
              >
                Take a carton off
              </button>
            )
          )}
          {loaded.length === 0 && (
            <button
              type="button"
              className="rf-button rf-button--quiet"
              disabled={busy}
              onClick={() => run(() => discardManifest(manifest.id), () => `${manifest.manifestNumber} discarded.`)}
            >
              Discard this manifest
            </button>
          )}
          <button type="button" className="rf-button rf-button--quiet" onClick={() => void leaveDoor()}>
            Different door
          </button>
        </>
      )}
    </>
  );
}

function CartonList({ title, rows }: { title: string; rows: { carton: { id: string; container: { lpnId: string } }; shipment: { shipmentNumber: string; missing: string[]; order: { orderNumber: string } } }[] }) {
  if (rows.length === 0) return null;
  return (
    <section className="rf-card" aria-label={title}>
      <p className="rf-card__step">{title}</p>
      <ul className="rf-list">
        {rows.map(({ carton, shipment }) => (
          <li key={carton.id}>
            <span className="code">{carton.container.lpnId}</span>
            <span className="muted">
              {shipment.order.orderNumber}
              {title === 'To load' && shipment.missing.length > 0 && ' · not ready'}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
