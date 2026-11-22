import { useCallback, useEffect, useState } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import {
  confirmPick,
  dropContainer,
  getPicking,
  releaseTask,
  scanMatches,
  startPicking,
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
          <section className="rf-card">
            <p className="rf-card__action">Scan an outbound container</p>
            <p className="muted">A tote, or the label of a new carton.</p>
          </section>
          <ScanInput label="Container" busy={busy} error={error} onScan={(lpn) => run(() => startPicking(lpn))} />
          <p className="rf-waiting muted">
            {state.waitingTasks === 0
              ? 'No pick tasks are waiting.'
              : `${state.waitingTasks} pick task${state.waitingTasks === 1 ? '' : 's'} waiting`}
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
                onClick={() => run(() => startPicking(state.newContainer!.lpnId, t.id))}
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

/** Which task, what it is picked into, and how far along it is. */
function TaskStrip({ task }: { task: TaskProgress }) {
  return (
    <section className="rf-strip" aria-label="Pick task">
      <div className="rf-strip__row">
        <h1 className="code">{task.taskNumber}</h1>
        {task.pickMethod === 'BATCH' && <span className="badge badge--sm badge--method-batch">Batch · {task.orderCount} orders</span>}
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
