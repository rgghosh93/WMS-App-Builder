import { useCallback, useEffect, useState } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import {
  confirmPick,
  dropContainer,
  getPicking,
  listPickZones,
  releaseTask,
  scanMatches,
  startPicking,
  type PickZone,
  type PickingState,
  type TaskProgress,
} from '../../api/rf';
import { PickStep } from './PickStep';
import { ScanInput } from './ScanInput';

/** Picking: scan a container, get a task, pick each detail, drop the container at a pack station. */
export function PickingTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const [state, setState] = useState<PickingState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  /** A container someone else is picking into, waiting for a yes to take it over. */
  const [takeOver, setTakeOver] = useState<{ lpnId: string; message: string } | null>(null);

  const [zones, setZones] = useState<PickZone[] | null>(null);
  const [chosen, setChosen] = useState(readZone);
  // A remembered zone counts only while it is there. Until that is known, it is taken to be.
  const zone = zones?.find((z) => z.id === chosen);
  const zoneId = zones ? zone?.id : chosen || undefined;

  // What waits in each zone changes as people pick, so look again each time a container is to be scanned.
  const scanning = state?.step === 'SCAN_CONTAINER' ? state : null;
  useEffect(() => {
    if (!scanning) return;
    const controller = new AbortController();
    listPickZones(controller.signal).then(setZones, () => undefined);
    return () => controller.abort();
  }, [scanning]);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        setState(await getPicking(signal));
        return true;
      } catch (e) {
        if (signal?.aborted) return false;
        if (e instanceof ApiError && e.status === 401) onSignedOut();
        else setError(errorMessage(e));
        return false;
      }
    },
    [onSignedOut],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  /** Runs one action. A conflict means things moved on, so the current state is reloaded. */
  const run = async (action: () => Promise<PickingState>, message?: (next: PickingState) => string | null) => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const next = await action();
      setState(next);
      setDone(message?.(next) ?? null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onSignedOut();
        return;
      }
      if (e instanceof ApiError && e.status === 409) await load();
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const start = async (lpnId: string, over = false) => {
    setBusy(true);
    setError(null);
    setDone(null);
    setTakeOver(null);
    try {
      setState(await startPicking(lpnId, null, over, zoneId));
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onSignedOut();
        return;
      }
      if (e instanceof ApiError && e.status === 409 && e.fieldErrors.assignedTo) {
        setTakeOver({ lpnId, message: e.message });
      } else {
        if (e instanceof ApiError && e.status === 409) await load();
        setError(errorMessage(e));
      }
    } finally {
      setBusy(false);
    }
  };

  if (!state) {
    return error ? (
      <>
        <p className="rf-error" role="alert">
          {error}
        </p>
        <button
          type="button"
          className="rf-button"
          onClick={() => {
            setError(null);
            void load();
          }}
        >
          Try again
        </button>
      </>
    ) : (
      <p className="muted">Loading…</p>
    );
  }

  const task = state.task;
  return (
    <>
      {task ? <TaskStrip task={task} /> : <h1 className="rf-title">Picking</h1>}
      {done && (
        <p className="rf-done" role="status">
          {done}
        </p>
      )}

      {state.step === 'SCAN_CONTAINER' && (
        <>
          {zones && zones.length > 0 && (
            <label className="scan">
              <span className="scan__label">Pick zone</span>
              <select
                className="scan__input"
                value={zone?.id ?? ''}
                disabled={busy}
                onChange={(e) => {
                  setChosen(e.target.value);
                  writeZone(e.target.value);
                  setError(null);
                }}
              >
                <option value="">Any zone</option>
                {zones.map((z) => (
                  <option key={z.id} value={z.id}>
                    {z.code} · {z.name} ({z.waiting} waiting)
                  </option>
                ))}
              </select>
            </label>
          )}
          <section className="rf-card">
            <p className="rf-card__action">Scan an outbound container</p>
            <p className="muted">A tote, or the label of a new carton.</p>
          </section>
          <ScanInput key={zoneId ?? 'any'} label="Container" busy={busy} error={error} onScan={(lpn) => void start(lpn)} />
          {takeOver && (
            <section className="rf-card" role="group" aria-label="Take over">
              <p className="rf-card__action">{takeOver.message}</p>
              <p className="muted">Only take it over if you have the container.</p>
              <div className="rf-choices">
                <button type="button" className="rf-button rf-button--primary" disabled={busy} onClick={() => void start(takeOver.lpnId, true)}>
                  Take over {takeOver.lpnId.toUpperCase()}
                </button>
                <button type="button" className="rf-button rf-button--quiet" onClick={() => setTakeOver(null)}>
                  Leave it
                </button>
              </div>
            </section>
          )}
          <p className="rf-waiting muted">
            {(zone ? zone.waiting : state.waitingTasks) === 0
              ? `No pick tasks are waiting${zone ? ` in ${zone.code}` : ''}.`
              : `${zone ? zone.waiting : state.waitingTasks} pick task${(zone ? zone.waiting : state.waitingTasks) === 1 ? '' : 's'} waiting${zone ? ` in ${zone.code}` : ''}`}
          </p>
        </>
      )}

      {state.step === 'CHOOSE_CONTAINER_TYPE' && state.newContainer && (
        <>
          <section className="rf-card">
            <p className="rf-card__action">New carton {state.newContainer.lpnId}</p>
            <p className="muted">Which carton is it?</p>
          </section>
          {error && (
            <p className="rf-error" role="alert">
              {error}
            </p>
          )}
          <div className="rf-choices">
            {state.newContainer.types.map((t) => (
              <button
                key={t.id}
                type="button"
                className="rf-button"
                disabled={busy}
                onClick={() => run(() => startPicking(state.newContainer!.lpnId, t.id, false, zoneId))}
              >
                <strong>{t.containerTypeId}</strong> {t.description}
              </button>
            ))}
          </div>
          <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => run(() => getPicking())}>
            Scan a different container
          </button>
        </>
      )}

      {state.step === 'PICK' && state.pick && task && (
        <PickStep
          key={state.pick.detailId}
          pick={state.pick}
          toLpnId={task.toLpnId}
          busy={busy}
          error={error}
          onClearError={() => setError(null)}
          onConfirm={(confirmation, summary) =>
            run(
              () => confirmPick(confirmation),
              (next) => (next.step === 'SCAN_CONTAINER' ? 'Nothing could be picked, so the task is closed.' : summary),
            )
          }
        />
      )}

      {state.step === 'DROP' && state.drop && task && (
        <>
          <section className="rf-card">
            <p className="rf-card__step">Task picked. Drop {task.toLpnId} at pack station</p>
            {state.drop.suggestedLocationId ? (
              <p className="rf-card__big code">{state.drop.suggestedLocationId}</p>
            ) : (
              <p className="rf-error" role="alert">
                No pack station is set up. Add a location of type Packing, then scan it here.
              </p>
            )}
          </section>
          <ScanInput
            label="Pack station"
            busy={busy}
            error={error}
            onScan={(station) =>
              run(
                () => dropContainer(station),
                () => {
                  const elsewhere = state.drop?.suggestedLocationId && !scanMatches(station, state.drop.suggestedLocationId);
                  return `${task.toLpnId} dropped at ${station.toUpperCase()}${elsewhere ? ' instead' : ''}. ${task.taskNumber} is complete.`;
                },
              )
            }
          />
        </>
      )}

      {task?.canRelease && (
        <button
          type="button"
          className="rf-button rf-button--quiet"
          disabled={busy}
          onClick={() => run(releaseTask, () => `${task.taskNumber} given back.`)}
        >
          Give this task back
        </button>
      )}
    </>
  );
}

const ZONE_KEY = 'wms.pickZone';

/** The pick zone chosen on this device before, if any. */
function readZone(): string {
  try {
    return localStorage.getItem(ZONE_KEY) ?? '';
  } catch {
    return '';
  }
}

function writeZone(id: string) {
  try {
    if (id) localStorage.setItem(ZONE_KEY, id);
    else localStorage.removeItem(ZONE_KEY);
  } catch {
    // only a convenience
  }
}

/** Which task, what it is picked into, and how far along it is. */
function TaskStrip({ task }: { task: TaskProgress }) {
  return (
    <section className="rf-strip" aria-label="Pick task">
      <div className="rf-strip__row">
        <h1 className="code">{task.taskNumber}</h1>
        {task.pickMethod === 'BATCH' && <span className="badge badge--sm badge--method-batch">Batch · {task.orderCount} orders</span>}
        {task.pickZone && <span className="badge badge--sm">Zone {task.pickZone}</span>}
        <span className="rf-strip__into">
          into <span className="code">{task.toLpnId}</span>
        </span>
      </div>
      <progress className="rf-progress" value={task.closedDetails} max={task.detailCount} aria-label="Picks done" />
      <span className="muted">
        {task.closedDetails === task.detailCount
          ? `All ${task.detailCount} picks done`
          : `Pick ${task.closedDetails + 1} of ${task.detailCount}`}
      </span>
    </section>
  );
}
