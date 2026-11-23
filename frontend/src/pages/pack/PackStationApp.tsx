import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { LogOut, Menu as MenuIcon } from 'lucide-react';
import { ApiError, errorMessage } from '../../api/http';
import {
  packStatusLabel,
  startPacking,
  stationList,
  stationQueue,
  type PackStation,
  type PackTask,
} from '../../api/packing';
import type { DeviceUser } from '../../api/rf';
import { SessionGate } from '../rf/SessionGate';
import { ScanInput } from '../rf/ScanInput';
import { PackScreen } from './PackScreen';

const STATION_KEY = 'wms.packStation';
const REFRESH_MS = 20_000;

/** The pack station app: sign in, choose the station, then pack the containers dropped there. */
export function PackStationApp() {
  return (
    <SessionGate>
      {({ user, leave, onSignedOut, signOutError }) => (
        <div className="station">
          <header className="rf-bar">
            <Link to="/rf" className="rf-bar__button" aria-label="Menu">
              <MenuIcon size={22} aria-hidden="true" />
            </Link>
            <span className="rf-bar__user">{user.displayName}</span>
            <button type="button" className="rf-bar__button" aria-label="Sign out" onClick={leave}>
              <LogOut size={20} aria-hidden="true" />
            </button>
          </header>
          <main className="station__main">
            {signOutError && (
              <p className="rf-error" role="alert">
                {signOutError}
              </p>
            )}
            <PackStation user={user} onSignedOut={onSignedOut} />
          </main>
        </div>
      )}
    </SessionGate>
  );
}

function PackStation({ user, onSignedOut }: { user: DeviceUser; onSignedOut: () => void }) {
  // Which station this screen stands at, remembered in this browser.
  const [station, setStation] = useState<string | null>(() => readStation());
  const [task, setTask] = useState<PackTask | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const choose = (locationId: string | null) => {
    setStation(locationId);
    writeStation(locationId);
    setDone(null);
  };

  if (!station) return <StationPicker onChoose={choose} onSignedOut={onSignedOut} />;
  if (task) {
    return (
      <PackScreen
        key={task.id}
        user={user}
        task={task}
        onSignedOut={onSignedOut}
        onBack={() => setTask(null)}
        onCompleted={(t) => {
          setTask(null);
          const cartons = t.cartons.length;
          setDone(
            cartons === 0
              ? `${t.taskNumber} is closed. Nothing was packed.`
              : `${t.taskNumber} is packed in ${cartons} carton${cartons === 1 ? '' : 's'}. ${
                  cartonsIncludeContainer(t) ? '' : `${t.container.lpnId} is empty.`
                }`.trim(),
          );
        }}
      />
    );
  }
  return (
    <Queue
      station={station}
      done={done}
      onStarted={(t) => {
        setDone(null);
        setTask(t);
      }}
      onChangeStation={() => choose(null)}
      onSignedOut={onSignedOut}
    />
  );
}

function cartonsIncludeContainer(task: PackTask): boolean {
  return task.cartons.some((c) => c.container.id === task.container.id);
}

// ---------- Choosing the station ----------

function StationPicker({ onChoose, onSignedOut }: { onChoose: (locationId: string) => void; onSignedOut: () => void }) {
  const [stations, setStations] = useState<PackStation[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    stationList(controller.signal)
      .then(setStations)
      .catch((e) => {
        if (controller.signal.aborted) return;
        if (e instanceof ApiError && e.status === 401) onSignedOut();
        else setError(errorMessage(e));
      });
    return () => controller.abort();
  }, [onSignedOut]);

  const scan = (value: string) => {
    const found = stations?.find((s) => s.locationId === value.trim().toUpperCase());
    setError(null);
    if (found) onChoose(found.locationId);
    else setTimeout(() => setError(`${value.toUpperCase()} is not a pack station`));
  };

  return (
    <div className="station__narrow">
      <h1 className="rf-title">Which pack station is this?</h1>
      <ScanInput label="Pack station" error={error} onScan={scan} />
      {stations?.length === 0 && <p className="muted">No pack stations yet. Add a location of type Packing.</p>}
      <div className="rf-choices">
        {stations?.map((s) => (
          <button key={s.id} type="button" className="rf-menu-item" onClick={() => onChoose(s.locationId)}>
            <span className="code">{s.locationId}</span>
            <span className="muted station__waiting">{s.waitingTasks} waiting</span>
          </button>
        ))}
      </div>
    </div>
  );
}

// ---------- What is waiting ----------

interface QueueProps {
  station: string;
  done: string | null;
  onStarted: (task: PackTask) => void;
  onChangeStation: () => void;
  onSignedOut: () => void;
}

function Queue({ station, done, onStarted, onChangeStation, onSignedOut }: QueueProps) {
  const [tasks, setTasks] = useState<PackTask[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** A container someone else is packing, waiting for a yes to take it over. */
  const [takeOver, setTakeOver] = useState<{ lpnId: string; message: string } | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        setTasks((await stationQueue(station, signal)).tasks);
      } catch (e) {
        if (signal?.aborted) return;
        if (e instanceof ApiError && e.status === 401) onSignedOut();
        else setError(errorMessage(e));
      }
    },
    [station, onSignedOut],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    const handle = setInterval(() => void load(controller.signal), REFRESH_MS);
    return () => {
      controller.abort();
      clearInterval(handle);
    };
  }, [load]);

  const start = async (lpnId: string, over = false) => {
    setBusy(true);
    setError(null);
    setTakeOver(null);
    try {
      onStarted(await startPacking(station, lpnId, over));
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) onSignedOut();
      else if (e instanceof ApiError && e.status === 409 && e.fieldErrors.assignedTo) setTakeOver({ lpnId, message: e.message });
      else setError(errorMessage(e));
      setBusy(false);
    }
  };

  return (
    <div className="station__narrow">
      <div className="station__station">
        <h1 className="code">{station}</h1>
        <button type="button" className="rf-button rf-button--quiet" onClick={onChangeStation}>
          Change station
        </button>
      </div>
      {done && (
        <p className="rf-done" role="status">
          {done}
        </p>
      )}
      <ScanInput label="Scan a container to pack" busy={busy} error={error} onScan={(lpn) => start(lpn)} />
      {takeOver && (
        <section className="rf-card" role="group" aria-label="Take over">
          <p className="rf-card__action">{takeOver.message}</p>
          <div className="rf-choices">
            <button type="button" className="rf-button rf-button--primary" disabled={busy} onClick={() => start(takeOver.lpnId, true)}>
              Take over {takeOver.lpnId.toUpperCase()}
            </button>
            <button type="button" className="rf-button rf-button--quiet" onClick={() => setTakeOver(null)}>
              Leave it
            </button>
          </div>
        </section>
      )}
      {tasks && (
        <>
          <h2>Waiting to be packed</h2>
          {tasks.length === 0 ? (
            <p className="muted">No containers are waiting here.</p>
          ) : (
            <ul className="station__queue" aria-label="Waiting containers">
              {tasks.map((t) => (
                <li key={t.id}>
                  <button type="button" className="station__queue-item" disabled={busy} onClick={() => start(t.container.lpnId)}>
                    <span className="code station__queue-lpn">{t.container.lpnId}</span>
                    <span className={`badge badge--task-${t.status === 'OPEN' ? 'open' : 'in-progress'}`}>{packStatusLabel(t.status)}</span>
                    <span className="station__queue-qty">
                      {(t.totalQuantity - t.packedQuantity - t.shortQuantity).toLocaleString()} EA to pack
                    </span>
                    <span className="muted station__queue-orders">
                      {t.orders.map((o) => o.orderNumber).join(', ')}
                      {t.assignedTo ? ` · ${t.assignedTo}` : ''}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}

function readStation(): string | null {
  try {
    return localStorage.getItem(STATION_KEY);
  } catch {
    return null;
  }
}

function writeStation(locationId: string | null) {
  try {
    if (locationId) localStorage.setItem(STATION_KEY, locationId);
    else localStorage.removeItem(STATION_KEY);
  } catch {
    // Storage is off: the station is asked for again next time.
  }
}
