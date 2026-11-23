import { useCallback, useEffect, useState } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import { inventoryStatusLabel } from '../../api/inventory';
import {
  OVERRIDE_REASONS,
  confirmPutaway,
  getPutaway,
  redirectPutaway,
  releasePutaway,
  startPutaway,
  type OverrideReason,
  type PutawayConfirm,
  type PutawayHandling,
  type PutawayState,
  type PutawayTask,
} from '../../api/putaway';
import { ScanInput } from './ScanInput';

/** Something the server wants confirmed before it puts the LPN in a location. */
type Question = { kind: 'elsewhere'; confirm: PutawayConfirm } | { kind: 'over'; confirm: PutawayConfirm; message: string };

/** Put-away: scan an LPN at receiving, take it where it is directed, scan the location. */
export function PutawayTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const [state, setState] = useState<PutawayState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [takeOver, setTakeOver] = useState<{ lpnId: string; message: string } | null>(null);
  const [question, setQuestion] = useState<Question | null>(null);
  const [skipping, setSkipping] = useState(false);
  /** Chosen here instead of what the rule said. */
  const [handling, setHandling] = useState<PutawayHandling | null>(null);

  const show = useCallback((next: PutawayState) => {
    setState((current) => {
      if (current?.task?.id !== next.task?.id) setHandling(null);
      return next;
    });
    setQuestion(null);
    setSkipping(false);
    setTakeOver(null);
  }, []);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        show(await getPutaway(signal));
      } catch (e) {
        if (signal?.aborted) return;
        if (e instanceof ApiError && e.status === 401) onSignedOut();
        else setError(errorMessage(e));
      }
    },
    [onSignedOut, show],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  /** Runs one action. A conflict that isn't a question means things moved on, so the current state is reloaded. */
  const run = async (action: () => Promise<PutawayState>, message: string | null, ask?: (e: ApiError) => boolean) => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      show(await action());
      setDone(message);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onSignedOut();
        return;
      }
      if (e instanceof ApiError && e.status === 409) {
        if (ask?.(e)) return;
        await load();
      }
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const start = (lpnId: string, over = false) =>
    run(() => startPutaway(lpnId, over), null, (e) => {
      if (!e.fieldErrors.assignedTo) return false;
      setTakeOver({ lpnId, message: e.message });
      return true;
    });

  const put = (task: PutawayTask, confirm: PutawayConfirm) => {
    const emptied = (confirm.handling ?? task.handling) === 'EMPTY_LPN';
    const to = confirm.locationId.toUpperCase();
    return run(() => confirmPutaway(task.id, confirm), emptied ? `${task.lpn.lpnId} emptied into ${to}.` : `${task.lpn.lpnId} put in ${to}.`, (e) => {
      if (e.fieldErrors.directed) setQuestion({ kind: 'elsewhere', confirm });
      else if (e.fieldErrors.capacity) setQuestion({ kind: 'over', confirm, message: e.message });
      else return false;
      return true;
    });
  };

  const errorNote = error && (
    <p className="rf-error" role="alert">
      {error}
    </p>
  );
  const doneNote = done && (
    <p className="rf-done" role="status">
      {done}
    </p>
  );

  if (!state) {
    return error ? (
      <>
        {errorNote}
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

  // ---------- Which LPN ----------

  const task = state.task;
  if (!task) {
    return (
      <>
        <h1 className="rf-title">Put-away</h1>
        {doneNote}
        <section className="rf-card">
          <p className="rf-card__action">Scan an LPN to put away</p>
        </section>
        <ScanInput label="LPN" busy={busy} error={error} onScan={(lpn) => void start(lpn)} />
        {takeOver && (
          <section className="rf-card" role="group" aria-label="Take over">
            <p className="rf-card__action">{takeOver.message}</p>
            <p className="muted">Only take it over if you have the LPN.</p>
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
        {state.waitingCount === 0 ? (
          <p className="rf-waiting muted">No LPNs are waiting to be put away.</p>
        ) : (
          <section className="rf-card" aria-label="Waiting">
            <p className="rf-card__step">
              {state.waitingCount.toLocaleString()} LPN{state.waitingCount === 1 ? '' : 's'} waiting
            </p>
            <ul className="rf-list">
              {state.waiting.map((t) => (
                <li key={t.id}>
                  <span className="code">{t.lpn.lpnId}</span>
                  <span className="muted">
                    {t.fromLocation.locationId} · {t.totalQuantity.toLocaleString()} EA
                  </span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </>
    );
  }

  // ---------- Where to ----------

  const how = handling ?? task.handling ?? 'KEEP_LPN';
  const directed = task.directedLocation;
  return (
    <>
      <section className="rf-strip" aria-label="Put-away task">
        <div className="rf-strip__row">
          <h1 className="code">{task.lpn.lpnId}</h1>
          <span>{task.lpn.containerType}</span>
        </div>
        <span className="muted">
          {task.taskNumber} · from {task.fromLocation.locationId}
        </span>
      </section>

      <section className="rf-card">
        {directed ? (
          <>
            <p className="rf-card__step">{how === 'EMPTY_LPN' ? 'Empty it into' : 'Put it in'}</p>
            <p className="rf-card__big code">{directed.locationId}</p>
          </>
        ) : (
          <>
            <p className="rf-card__action">No location with room was found</p>
            <p className="muted">
              {how === 'EMPTY_LPN' ? 'Empty it into' : 'Put it in'} any storage location that can take it, and scan that.
            </p>
          </>
        )}
        <dl className="rf-facts">
          {task.lines.slice(0, 4).map((l, i) => (
            <div className="rf-fact" key={i}>
              <dt>{l.quantity.toLocaleString()} EA</dt>
              <dd>
                <span className="code">{l.item.itemId}</span>
                <span className="muted rf-fact__detail">
                  {[l.item.description, l.lotNumber && `lot ${l.lotNumber}`, l.status !== 'AVAILABLE' && inventoryStatusLabel(l.status)]
                    .filter(Boolean)
                    .join(' · ')}
                </span>
              </dd>
            </div>
          ))}
        </dl>
        {task.lines.length > 4 && <p className="muted">and {task.lines.length - 4} more</p>}
      </section>

      {question?.kind === 'elsewhere' && directed ? (
        <section className="rf-card" role="group" aria-label="Another location">
          <p className="rf-card__action">
            Not {directed.locationId}. Why {question.confirm.locationId.toUpperCase()} instead?
          </p>
          <div className="rf-choices">
            {OVERRIDE_REASONS.map((r) => (
              <button key={r.value} type="button" className="rf-button" disabled={busy} onClick={() => void put(task, { ...question.confirm, reason: r.value })}>
                {r.label}
              </button>
            ))}
            <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => setQuestion(null)}>
              Go to {directed.locationId}
            </button>
          </div>
        </section>
      ) : question?.kind === 'over' ? (
        <section className="rf-card" role="group" aria-label="No room">
          <p className="rf-card__action">{question.message}</p>
          <div className="rf-choices">
            <button type="button" className="rf-button rf-button--danger" disabled={busy} onClick={() => void put(task, { ...question.confirm, allowOver: true })}>
              Put it there anyway
            </button>
            <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => setQuestion(null)}>
              Scan a different location
            </button>
          </div>
        </section>
      ) : skipping && directed ? (
        <section className="rf-card" role="group" aria-label="Can't use it">
          <p className="rf-card__action">What is wrong with {directed.locationId}?</p>
          {errorNote}
          <div className="rf-choices">
            {OVERRIDE_REASONS.map((r: { value: OverrideReason; label: string }) => (
              <button
                key={r.value}
                type="button"
                className="rf-button"
                disabled={busy}
                onClick={() => void run(() => redirectPutaway(task.id, directed.locationId, r.value), `Passed over ${directed.locationId}.`)}
              >
                {r.label}
              </button>
            ))}
            <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => setSkipping(false)}>
              Go back
            </button>
          </div>
        </section>
      ) : (
        <>
          {doneNote}
          <ScanInput
            key={`${directed?.id ?? 'any'}-${how}`}
            label="Scan location"
            busy={busy}
            error={error}
            onScan={(code) => void put(task, { locationId: code, reason: null, allowOver: false, handling })}
          />
          {directed && (
            <button
              type="button"
              className="rf-button"
              disabled={busy}
              onClick={() => {
                setError(null);
                setDone(null);
                setSkipping(true);
              }}
            >
              Can't use this location
            </button>
          )}
          <button
            type="button"
            className="rf-button rf-button--quiet"
            disabled={busy}
            onClick={() => setHandling(how === 'EMPTY_LPN' ? 'KEEP_LPN' : 'EMPTY_LPN')}
          >
            {how === 'EMPTY_LPN' ? 'Keep it in its LPN instead' : 'Empty it into the location instead'}
          </button>
          <button
            type="button"
            className="rf-button rf-button--quiet"
            disabled={busy}
            onClick={() => void run(() => releasePutaway(task.id), `${task.lpn.lpnId} is back at ${task.fromLocation.locationId}.`)}
          >
            Put it back at {task.fromLocation.locationId}
          </button>
        </>
      )}
    </>
  );
}
