import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError, errorMessage } from '../../api/http';
import { startCount } from '../../api/control';
import { startPutaway } from '../../api/putaway';
import { startReplenish } from '../../api/replenishment';
import { scanMatches, startPicking, type ContainerTypeOption } from '../../api/rf';
import { getNextTask, type NextTask, type QueuedTask, type WorkKind } from '../../api/tasks';
import { ScanInput } from './ScanInput';
import { useRf } from './useRf';

/** Where each kind of work is done. */
const PLACES: Record<WorkKind, string> = {
  PUTAWAY: '/rf/putaway',
  REPLENISHMENT: '/rf/replenish',
  COUNT: '/rf/count',
  PICK: '/rf/picking',
  PACK: '/pack',
};

/** What to do next, whatever kind of work it is. */
export function NextTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const rf = useRf(onSignedOut);
  const navigate = useNavigate();
  const [next, setNext] = useState<NextTask | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  /** Tasks passed over until something is started. */
  const [skip, setSkip] = useState<string[]>([]);
  /** A new carton to pick into, waiting to hear which kind. */
  const [carton, setCarton] = useState<{ lpnId: string; types: ContainerTypeOption[] } | null>(null);

  const load = useCallback(
    async (passed: string[], signal?: AbortSignal) => {
      setFailed(null);
      try {
        setNext(await getNextTask(passed, signal));
        setCarton(null);
      } catch (e) {
        if (signal?.aborted) return;
        if (e instanceof ApiError && e.status === 401) onSignedOut();
        else setFailed(errorMessage(e));
      }
    },
    [onSignedOut],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(skip, controller.signal);
    return () => controller.abort();
  }, [load, skip]);

  if (!next || failed) {
    return failed ? (
      <>
        <p className="rf-error" role="alert">
          {failed}
        </p>
        <button type="button" className="rf-button" onClick={() => void load(skip)}>
          Try again
        </button>
      </>
    ) : (
      <p className="muted">Loading…</p>
    );
  }

  /** Starts the task and goes to where it is done. If it can't be started, looks again. */
  const start = async (task: QueuedTask, action: () => Promise<boolean>) => {
    let conflict = false;
    const worked = await rf.run(
      async () => {
        if (await action()) navigate(PLACES[task.kind]);
      },
      (e) => {
        conflict = e.status === 409;
        return false;
      },
    );
    if (!worked && conflict) void load(skip);
  };

  const pick = (task: QueuedTask, lpnId: string, typeId: string | null) =>
    void start(task, async () => {
      const state = await startPicking(lpnId, typeId, false, undefined, task.id);
      if (state.step !== 'CHOOSE_CONTAINER_TYPE') return true;
      setCarton(state.newContainer);
      return false;
    });

  if (next.doing.length > 0) {
    return (
      <>
        <h1 className="rf-title">Finish this first</h1>
        <div className="rf-choices">
          {next.doing.map((t) => (
            <Link key={t.id} to={PLACES[t.kind]} className="rf-menu-item">
              <span>
                <strong>{t.label}</strong> <span className="code">{t.number}</span>
                <span className="muted cell__sub">{t.what}</span>
              </span>
            </Link>
          ))}
        </div>
      </>
    );
  }

  const task = next.offer;
  const error = rf.error && (
    <p className="rf-error" role="alert">
      {rf.error}
    </p>
  );
  if (!task) {
    return (
      <>
        <h1 className="rf-title">Next task</h1>
        {error}
        <p className="muted">{next.waiting > 0 ? 'There is nothing else. You passed over the rest.' : 'There is nothing for you to do now.'}</p>
        <button
          type="button"
          className="rf-button"
          onClick={() => {
            rf.clear();
            if (skip.length > 0) setSkip([]);
            else void load([]);
          }}
        >
          Look again
        </button>
      </>
    );
  }

  return (
    <>
      <section className="rf-strip" aria-label="Next task">
        <div className="rf-strip__row">
          <h1 className="code">{task.number}</h1>
          <span className="badge badge--sm">{task.label}</span>
          <span className="rf-strip__into">{task.assignedTo ? 'assigned to you' : `priority ${task.priority}`}</span>
        </div>
      </section>
      <section className="rf-card" aria-label="What to do">
        <p className="rf-card__action">{task.what}</p>
        {task.location && (
          <>
            <p className="rf-card__step">{task.kind === 'PICK' ? 'Starts at' : 'Go to'}</p>
            <p className="rf-card__big code">{task.location}</p>
          </>
        )}
      </section>

      {carton ? (
        <>
          <p className="rf-card__action">New carton {carton.lpnId}. Which carton is it?</p>
          {error}
          <div className="rf-choices">
            {carton.types.map((t) => (
              <button key={t.id} type="button" className="rf-button" disabled={rf.busy} onClick={() => pick(task, carton.lpnId, t.id)}>
                <strong>{t.containerTypeId}</strong> {t.description}
              </button>
            ))}
            <button type="button" className="rf-button rf-button--quiet" disabled={rf.busy} onClick={() => setCarton(null)}>
              Scan a different container
            </button>
          </div>
        </>
      ) : task.kind === 'PICK' ? (
        <ScanInput key={task.id} label="Scan a container to pick into" busy={rf.busy} error={rf.error} onScan={(lpn) => pick(task, lpn, null)} />
      ) : task.kind === 'PUTAWAY' ? (
        <ScanInput
          key={task.id}
          label={`Scan LPN ${task.lpn}`}
          busy={rf.busy}
          error={rf.error}
          onScan={(lpn) =>
            scanMatches(lpn, task.lpn!) ? void start(task, () => startPutaway(lpn).then(() => true)) : rf.problem(`Wrong LPN. Scan ${task.lpn}`)
          }
        />
      ) : task.kind === 'COUNT' ? (
        <ScanInput
          key={task.id}
          label="Scan location"
          busy={rf.busy}
          error={rf.error}
          onScan={(code) =>
            scanMatches(code, task.location!) ? void start(task, () => startCount(code, false).then(() => true)) : rf.problem(`Wrong location. Go to ${task.location}`)
          }
        />
      ) : (
        <>
          {error}
          <button
            type="button"
            className="rf-button rf-button--primary"
            disabled={rf.busy}
            onClick={() => void start(task, () => startReplenish(task.id).then(() => true))}
          >
            Start
          </button>
        </>
      )}
      <button
        type="button"
        className="rf-button rf-button--quiet"
        disabled={rf.busy}
        onClick={() => {
          rf.clear();
          setSkip([...skip, task.id]);
        }}
      >
        Something else
      </button>
      <p className="rf-waiting muted">{`${next.waiting} task${next.waiting === 1 ? '' : 's'} you could do`}</p>
    </>
  );
}
