import { useCallback, useEffect, useState } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import {
  confirmReplenish,
  getReplenish,
  inUnits,
  reasonLabel,
  releaseReplenish,
  startReplenish,
  type ReplenishState,
  type ReplenishmentTask,
} from '../../api/replenishment';
import { formatUnits, scanMatches, type ItemScan } from '../../api/rf';
import { formatDay } from '../outbound/OrdersPage';
import { ScanInput } from './ScanInput';
import { useRf } from './useRf';

/** Replenishing: take stock from storage and put it in a pick location. */
export function ReplenishTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const rf = useRf(onSignedOut);
  const [state, setState] = useState<ReplenishState | null>(null);
  const [failed, setFailed] = useState<string | null>(null);

  const load = useCallback(
    (signal?: AbortSignal) => {
      setFailed(null);
      getReplenish(signal)
        .then(setState)
        .catch((e) => {
          if (signal?.aborted) return;
          if (e instanceof ApiError && e.status === 401) onSignedOut();
          else setFailed(errorMessage(e));
        });
    },
    [onSignedOut],
  );
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  if (!state) {
    return failed ? (
      <>
        <p className="rf-error" role="alert">
          {failed}
        </p>
        <button type="button" className="rf-button" onClick={() => load()}>
          Try again
        </button>
      </>
    ) : (
      <p className="muted">Loading…</p>
    );
  }

  const doneNote = rf.done && (
    <p className="rf-done" role="status">
      {rf.done}
    </p>
  );

  if (state.task) {
    return (
      <>
        {doneNote}
        <Moving
          key={`${state.task.id}-${state.task.movedQuantity}`}
          task={state.task}
          rf={rf}
          onState={setState}
          onLost={() => load()}
        />
      </>
    );
  }

  const start = (id: string | null) =>
    void rf.run(async () => setState(await startReplenish(id))).then((worked) => {
      if (!worked) load();
    });

  return (
    <>
      <h1 className="rf-title">Replenish</h1>
      {doneNote}
      {rf.error && (
        <p className="rf-error" role="alert">
          {rf.error}
        </p>
      )}
      {state.waitingCount === 0 ? (
        <>
          <p className="muted">No replenishment is waiting.</p>
          <button type="button" className="rf-button" disabled={rf.busy} onClick={() => load()}>
            Look again
          </button>
        </>
      ) : (
        <>
          <button type="button" className="rf-button rf-button--primary" disabled={rf.busy} onClick={() => start(null)}>
            Start the most urgent
          </button>
          <p className="rf-waiting muted">{`${state.waitingCount} task${state.waitingCount === 1 ? '' : 's'} waiting`}</p>
          <div className="rf-choices">
            {state.waiting.map((t) => (
              <button key={t.id} type="button" className="rf-button" disabled={rf.busy} onClick={() => start(t.id)}>
                <strong className="code">{t.toLocation.locationId}</strong> {t.quantity.toLocaleString()} EA of {t.item.itemId}
                <span className="muted cell__sub">
                  from {t.fromLocation.locationId} · {reasonLabel(t.reason)}
                </span>
              </button>
            ))}
          </div>
        </>
      )}
    </>
  );
}

interface MovingProps {
  task: ReplenishmentTask;
  rf: ReturnType<typeof useRf>;
  onState: (state: ReplenishState) => void;
  /** The task may not be ours any more. */
  onLost: () => void;
}

/** One trip of a task, a scan at a time. Nothing changes until it is put in the pick location. */
function Moving({ task, rf, onState, onLost }: MovingProps) {
  const remaining = task.quantity - task.movedQuantity - task.shortQuantity;
  const [location, setLocation] = useState<string | null>(null);
  const [lpn, setLpn] = useState<string | null>(null);
  const [scan, setScan] = useState<{ value: string; unit: ItemScan } | null>(null);
  /** The last item scanned, for the server to check. */
  const [barcode, setBarcode] = useState<string | null>(null);
  const [taken, setTaken] = useState(0);
  /** On the way, with what was taken; shortage if the rest isn't there. */
  const [going, setGoing] = useState<{ shortage: boolean } | null>(null);
  const [over, setOver] = useState<{ to: string; message: string } | null>(null);

  const attempt = (problem: string | null, accept: () => void) => {
    if (problem) return rf.problem(problem);
    rf.clear();
    accept();
  };

  const left = remaining - taken;
  const scanItem = (value: string) => {
    const unit = task.scans.find((s) => scanMatches(value, s.value));
    attempt(
      !unit
        ? `Wrong item. Take ${task.item.itemId}`
        : unit.eachesPerUnit > left
          ? `One ${unit.uom} is ${unit.eachesPerUnit} EA, but only ${left} EA more are wanted. Scan a smaller unit`
          : null,
      () => {
        setScan({ value, unit: unit! });
        setBarcode(value);
      },
    );
  };

  const enter = (text: string) => {
    const eaches = (/^[0-9]+$/.test(text) ? Number(text) : 0) * scan!.unit.eachesPerUnit;
    attempt(eaches < 1 ? 'Enter a whole number' : eaches > left ? `Only ${left} EA more are wanted` : null, () => {
      setTaken(taken + eaches);
      setScan(null);
      if (eaches === left) setGoing({ shortage: false });
    });
  };

  const send = async (to: string | null, shortage: boolean, quantity: number, allowOver = false) => {
    let asked = false;
    const worked = await rf.run(
      async () => {
        const next = await confirmReplenish(task.id, {
          movedSoFar: task.movedQuantity,
          fromLocationId: location!,
          lpnId: lpn,
          itemBarcode: barcode,
          quantity,
          shortage,
          toLocationId: to,
          allowOver,
        });
        onState(next);
        rf.setDone(
          quantity > 0
            ? `Put ${quantity.toLocaleString()} EA of ${task.item.itemId} in ${task.toLocation.locationId}${shortage ? '. The rest is on hold' : ''}.`
            : `${task.taskNumber} closed. What should be there is on hold.`,
        );
      },
      (e) => {
        if (e.status !== 409 || !e.fieldErrors.capacity) return false;
        asked = true;
        setOver({ to: to!, message: e.message });
        return true;
      },
    );
    // It may have been called off meanwhile. If not, everything scanned stays.
    if (!worked && !asked) onLost();
  };

  const giveBack = () =>
    void rf.run(async () => onState(await releaseReplenish(task.id))).then((worked) => {
      if (!worked) onLost();
    });

  const strip = (
    <section className="rf-strip" aria-label="Replenishment task">
      <div className="rf-strip__row">
        <h1 className="code">{task.taskNumber}</h1>
        <span className="badge badge--sm">{reasonLabel(task.reason)}</span>
        <span className="rf-strip__into">
          to <span className="code">{task.toLocation.locationId}</span>
        </span>
      </div>
      {task.movedQuantity > 0 && (
        <span className="muted">
          {task.movedQuantity.toLocaleString()} of {task.quantity.toLocaleString()} EA moved
        </span>
      )}
    </section>
  );

  const back = (
    <button type="button" className="rf-button rf-button--quiet" disabled={rf.busy} onClick={giveBack}>
      Give this task back
    </button>
  );

  if (over) {
    return (
      <>
        {strip}
        <section className="rf-card" role="group" aria-label="No room">
          <p className="rf-card__action">{over.message}</p>
          <div className="rf-choices">
            <button type="button" className="rf-button rf-button--danger" disabled={rf.busy} onClick={() => void send(over.to, going!.shortage, taken, true)}>
              Put it there anyway
            </button>
            <button type="button" className="rf-button rf-button--quiet" disabled={rf.busy} onClick={() => setOver(null)}>
              Not now
            </button>
          </div>
        </section>
      </>
    );
  }

  if (going) {
    return (
      <>
        {strip}
        <section className="rf-card" aria-label="Where to">
          <p className="rf-card__step">
            Put {formatUnits(inUnits(taken, task.scans))} of {task.item.itemId} in
          </p>
          <p className="rf-card__big code">{task.toLocation.locationId}</p>
        </section>
        <ScanInput
          label="Pick location"
          busy={rf.busy}
          error={rf.error}
          onScan={(to) =>
            scanMatches(to, task.toLocation.locationId) ? void send(to, going.shortage, taken) : rf.problem(`Wrong location. Put it in ${task.toLocation.locationId}`)
          }
        />
        <button
          type="button"
          className="rf-button rf-button--quiet"
          disabled={rf.busy}
          onClick={() => {
            rf.clear();
            setTaken(0);
            setGoing(null);
          }}
        >
          Count what I took again
        </button>
        {back}
      </>
    );
  }

  const step = !location ? 'location' : task.fromLpn && !lpn ? 'lpn' : scan ? 'quantity' : 'item';
  const facts = [task.lotNumber && `lot ${task.lotNumber}`, task.expiryDate && `expires ${formatDay(task.expiryDate)}`].filter(Boolean).join(' · ');

  return (
    <>
      {strip}
      <section className="rf-card" aria-label="What to take">
        {step === 'location' || step === 'lpn' ? (
          <>
            <p className="rf-card__step">{step === 'location' ? 'Go to' : 'Take it from'}</p>
            <p className="rf-card__big code">{step === 'location' ? task.fromLocation.locationId : task.fromLpn}</p>
          </>
        ) : (
          <>
            <p className="rf-card__step">
              Take from <span className="code">{task.fromLpn ?? task.fromLocation.locationId}</span>
            </p>
            <p className="rf-card__big">{formatUnits(inUnits(left, task.scans))}</p>
          </>
        )}
        <p>
          <span className="code">{task.item.itemId}</span> {task.item.description}
        </p>
        {facts && <p className="muted">{facts}</p>}
        {taken > 0 && <p className="muted">{taken.toLocaleString()} EA taken so far</p>}
      </section>
      {step === 'location' && (
        <ScanInput
          label="Scan location"
          busy={rf.busy}
          error={rf.error}
          onScan={(v) => attempt(scanMatches(v, task.fromLocation.locationId) ? null : `Wrong location. Go to ${task.fromLocation.locationId}`, () => setLocation(v))}
        />
      )}
      {step === 'lpn' && (
        <ScanInput
          label="Scan LPN"
          busy={rf.busy}
          error={rf.error}
          onScan={(v) => attempt(scanMatches(v, task.fromLpn!) ? null : `Wrong LPN. Take it from ${task.fromLpn}`, () => setLpn(v))}
        />
      )}
      {step === 'item' && <ScanInput label="Scan item" busy={rf.busy} error={rf.error} onScan={scanItem} />}
      {step === 'quantity' && (
        <ScanInput
          label={`Quantity (${scan!.unit.uom}${scan!.unit.eachesPerUnit > 1 ? ` of ${scan!.unit.eachesPerUnit} EA` : ''}), up to ${Math.floor(left / scan!.unit.eachesPerUnit).toLocaleString()}`}
          busy={rf.busy}
          error={rf.error}
          inputMode="numeric"
          onScan={enter}
        />
      )}
      {step === 'item' && (
        <div className="rf-choices">
          {taken > 0 && (
            <button type="button" className="rf-button" disabled={rf.busy} onClick={() => setGoing({ shortage: false })}>
              Put these in {task.toLocation.locationId} and come back
            </button>
          )}
          <button type="button" className="rf-button" disabled={rf.busy} onClick={() => (taken > 0 ? setGoing({ shortage: true }) : void send(null, true, 0))}>
            {taken > 0 ? 'There is no more' : 'There is none'}
          </button>
        </div>
      )}
      {back}
    </>
  );
}
