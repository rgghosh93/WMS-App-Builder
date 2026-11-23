import { useCallback, useEffect, useState } from 'react';
import {
  enterCount,
  finishCount,
  getCountItem,
  getCounting,
  giveBackCount,
  removeCountEntry,
  startCount,
  type ControlItem,
  type CountEntryInput,
  type CountState,
} from '../../api/control';
import { ApiError, errorMessage } from '../../api/http';
import type { ItemScan } from '../../api/rf';
import { newRequestId } from '../../forms/requestId';
import { ScanInput } from './ScanInput';
import { useRf } from './useRf';

interface Counted {
  item: ControlItem;
  scan: ItemScan;
  code: string;
  lot: string | null;
  serials: string[];
}

/** Counting a location without seeing what is expected there. */
export function CountTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const rf = useRf(onSignedOut);
  const [state, setState] = useState<CountState | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  /** The LPN being counted; null for what lies loose. */
  const [lpn, setLpn] = useState<string | null>(null);
  const [scanningLpn, setScanningLpn] = useState(false);
  const [counted, setCounted] = useState<Counted | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [takeOver, setTakeOver] = useState<{ location: string; message: string } | null>(null);
  /** An entry that got no answer, sent again under the same ID. */
  const [pending, setPending] = useState<CountEntryInput | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        setState(await getCounting(signal));
        setLoadError(null);
      } catch (e) {
        if (signal?.aborted) return;
        if (e instanceof ApiError && e.status === 401) onSignedOut();
        else setLoadError(errorMessage(e));
      }
    },
    [onSignedOut],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const show = (next: CountState) => {
    setState(next);
    setCounted(null);
    setTakeOver(null);
    setPending(null);
    if (!next.counting) {
      setLpn(null);
      setScanningLpn(false);
      setConfirming(false);
    }
    if (next.message) rf.setDone(next.message);
  };

  /** A conflict means someone else took over or cancelled it, so the current state is loaded. */
  const act = (action: () => Promise<CountState>, done?: string) =>
    rf.run(async () => {
      const next = await action();
      show(next);
      if (done) rf.setDone(done);
    }).then((ok) => {
      if (!ok) void load();
      return ok;
    });

  const start = (location: string, over = false) =>
    rf.run(
      async () => show(await startCount(location, over)),
      (e) => {
        if (e.status !== 409 || !e.fieldErrors.assignedTo) return false;
        setTakeOver({ location, message: e.message });
        return true;
      },
    );

  const send = (entry: CountEntryInput, done: string) =>
    rf.run(async () => {
      try {
        show(await enterCount(state!.counting!.id, entry));
        rf.setDone(done);
      } catch (e) {
        // No answer: it may have got through, so it is kept and sent again as it was.
        if (!(e instanceof ApiError) || e.status >= 500) setPending(entry);
        else if (e.status === 409) void load();
        throw e;
      }
    });

  const enter = (c: Counted, quantity: number | null) =>
    send(
      { requestId: newRequestId(), lpnId: lpn, itemBarcode: c.code, quantity, lotNumber: c.lot, serialNumbers: c.serials },
      `Counted ${(quantity ?? c.serials.length).toLocaleString()} ${c.item.serialControlled ? 'EA' : c.scan.uom} of ${c.item.itemId}`,
    );

  if (!state) {
    return loadError ? (
      <>
        <p className="rf-error" role="alert">
          {loadError}
        </p>
        <button type="button" className="rf-button" onClick={() => void load()}>
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
  const errorNote = rf.error && (
    <p className="rf-error" role="alert">
      {rf.error}
    </p>
  );

  if (pending) {
    return (
      <>
        <h1 className="rf-title">Count</h1>
        <section className="rf-card" role="alert">
          <p className="rf-card__action">No answer from the server</p>
          <p className="muted">Send it again when you have a signal. It won't be counted twice.</p>
        </section>
        <button type="button" className="rf-button rf-button--primary" disabled={rf.busy} onClick={() => void send(pending, 'Counted')}>
          {rf.busy ? 'Sending…' : 'Send again'}
        </button>
      </>
    );
  }

  // ---------- Where ----------

  const counting = state.counting;
  if (!counting) {
    return (
      <>
        <h1 className="rf-title">Count</h1>
        {doneNote}
        <section className="rf-card">
          <p className="rf-card__action">{state.waiting.length > 0 ? `Go to ${state.waiting[0].location}` : 'Scan a location to count'}</p>
        </section>
        <ScanInput label="Location" busy={rf.busy} error={rf.error} onScan={(code) => void start(code)} />
        {takeOver && (
          <section className="rf-card" role="group" aria-label="Take over">
            <p className="rf-card__action">{takeOver.message}</p>
            <p className="muted">You carry on with what is counted so far.</p>
            <div className="rf-choices">
              <button type="button" className="rf-button rf-button--primary" disabled={rf.busy} onClick={() => void start(takeOver.location, true)}>
                Take over {takeOver.location.toUpperCase()}
              </button>
              <button type="button" className="rf-button rf-button--quiet" onClick={() => setTakeOver(null)}>
                Leave it
              </button>
            </div>
          </section>
        )}
        {state.waitingCount === 0 ? (
          <p className="rf-waiting muted">No counts are planned.</p>
        ) : (
          <section className="rf-card" aria-label="Waiting">
            <p className="rf-card__step">
              {state.waitingCount.toLocaleString()} location{state.waitingCount === 1 ? '' : 's'} to count
            </p>
            <ul className="rf-list">
              {state.waiting.map((w) => (
                <li key={w.number}>
                  <span className="code">{w.location}</span>
                  {w.again && <span className="muted">count again</span>}
                </li>
              ))}
            </ul>
          </section>
        )}
      </>
    );
  }

  const strip = (
    <section className="rf-strip" aria-label="Count">
      <div className="rf-strip__row">
        <h1 className="code">{counting.location}</h1>
        {lpn && (
          <span className="rf-strip__into">
            in <span className="code">{lpn}</span>
          </span>
        )}
      </div>
      <span className="muted">{counting.number}</span>
    </section>
  );

  // ---------- Done? ----------

  if (confirming) {
    return (
      <>
        {strip}
        {errorNote}
        <section className="rf-card" role="group" aria-label="Finish">
          <p className="rf-card__action">Is everything at {counting.location} counted?</p>
          <p className="muted">
            {counting.entries.length === 0 ? 'You counted nothing, so it will be taken as empty.' : 'What you did not count will be taken as missing.'}
          </p>
          <div className="rf-choices">
            <button type="button" className="rf-button rf-button--primary" disabled={rf.busy} onClick={() => void act(() => finishCount(counting.id))}>
              {counting.entries.length === 0 ? 'Yes, it is empty' : 'Yes, everything'}
            </button>
            <button type="button" className="rf-button rf-button--quiet" disabled={rf.busy} onClick={() => setConfirming(false)}>
              Keep counting
            </button>
          </div>
        </section>
      </>
    );
  }

  // ---------- One item ----------

  if (counted) {
    const { item, scan, lot, serials } = counted;
    const needsLot = item.lotControlled && !lot;
    return (
      <>
        {strip}
        <section className="rf-card">
          <p className="rf-card__big code">{item.itemId}</p>
          <p>{item.description}</p>
          {lot && (
            <p>
              Lot <span className="code">{lot}</span>
            </p>
          )}
        </section>
        {needsLot ? (
          <ScanInput
            key="lot"
            label="Lot"
            busy={rf.busy}
            error={rf.error}
            onScan={(value) => {
              rf.clear();
              setCounted({ ...counted, lot: value.toUpperCase() });
            }}
          />
        ) : item.serialControlled ? (
          <>
            <ScanInput
              key="serial"
              label={`Serial number ${serials.length + 1}`}
              busy={rf.busy}
              error={rf.error}
              onScan={(value) => {
                const serial = value.toUpperCase();
                if (serials.includes(serial)) return rf.problem(`${serial} is already scanned`);
                rf.clear();
                setCounted({ ...counted, serials: [...serials, serial] });
              }}
            />
            {serials.length > 0 && (
              <button type="button" className="rf-button rf-button--primary" disabled={rf.busy} onClick={() => void enter(counted, null)}>
                Count {serials.length}
              </button>
            )}
          </>
        ) : (
          <ScanInput
            key="quantity"
            label={`How many (${scan.uom}${scan.eachesPerUnit > 1 ? ` of ${scan.eachesPerUnit} EA` : ''})`}
            busy={rf.busy}
            error={rf.error}
            inputMode="numeric"
            onScan={(text) => {
              const quantity = /^[0-9]+$/.test(text) ? Number(text) : 0;
              if (quantity < 1) return rf.problem('Enter a whole number');
              void enter(counted, quantity);
            }}
          />
        )}
        <button
          type="button"
          className="rf-button rf-button--quiet"
          disabled={rf.busy}
          onClick={() => {
            rf.clear();
            setCounted(null);
          }}
        >
          Different item
        </button>
      </>
    );
  }

  // ---------- Which LPN ----------

  if (scanningLpn) {
    return (
      <>
        {strip}
        <section className="rf-card">
          <p className="rf-card__action">Scan the LPN you are counting</p>
        </section>
        <ScanInput
          label="LPN"
          busy={rf.busy}
          error={rf.error}
          onScan={(code) => {
            rf.clear();
            setLpn(code.toUpperCase());
            setScanningLpn(false);
          }}
        />
        <button type="button" className="rf-button rf-button--quiet" onClick={() => setScanningLpn(false)}>
          Go back
        </button>
      </>
    );
  }

  // ---------- Counting ----------

  return (
    <>
      {strip}
      {doneNote}
      <section className="rf-card">
        <p className="rf-card__action">{lpn ? `Scan an item in ${lpn}` : 'Scan an item lying loose'}</p>
      </section>
      <ScanInput
        key={lpn ?? 'loose'}
        label="Item"
        busy={rf.busy}
        error={rf.error}
        onScan={(code) =>
          void rf.run(async () => {
            const found = await getCountItem(code);
            setCounted({ ...found, code, lot: null, serials: [] });
          })
        }
      />
      <div className="rf-choices">
        <button type="button" className="rf-button" disabled={rf.busy} onClick={() => setScanningLpn(true)}>
          {lpn ? 'Count another LPN' : 'Count inside an LPN'}
        </button>
        {lpn && (
          <button type="button" className="rf-button" disabled={rf.busy} onClick={() => setLpn(null)}>
            Count what lies loose
          </button>
        )}
        <button
          type="button"
          className="rf-button rf-button--primary"
          disabled={rf.busy}
          onClick={() => {
            rf.clear();
            setConfirming(true);
          }}
        >
          Finish
        </button>
      </div>
      {counting.entries.length > 0 && (
        <section className="rf-card" aria-label="Counted so far">
          <p className="rf-card__step">Counted so far</p>
          <ul className="rf-list">
            {counting.entries.map((e) => (
              <li key={e.id}>
                <span>
                  <span className="code">{e.item.itemId}</span> {e.quantity.toLocaleString()} EA
                  <span className="muted cell__sub">{[e.lpn && `in ${e.lpn}`, e.lotNumber && `lot ${e.lotNumber}`, e.serialNumber].filter(Boolean).join(' · ')}</span>
                </span>
                <button
                  type="button"
                  className="rf-link"
                  aria-label={`Remove ${e.quantity} EA of ${e.item.itemId}`}
                  disabled={rf.busy}
                  onClick={() => void act(() => removeCountEntry(counting.id, e.id))}
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
      <button type="button" className="rf-button rf-button--quiet" disabled={rf.busy} onClick={() => void act(() => giveBackCount(counting.id))}>
        Give this count back
      </button>
    </>
  );
}
