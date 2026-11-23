import { useEffect, useState } from 'react';
import { getMoveSource, listPutBackOnDevice, moveLoose, moveLpn, type MovableLine, type MoveSource, type PutBack } from '../../api/control';
import { inventoryStatusLabel } from '../../api/inventory';
import { scanMatches, type ItemScan } from '../../api/rf';
import { newRequestId } from '../../forms/requestId';
import { formatDay } from '../outbound/OrdersPage';
import { itemsOf } from '../packing/PutBackNote';
import { ScanInput } from './ScanInput';
import { useRf } from './useRf';

interface Loose {
  line: MovableLine;
  scan: ItemScan;
  /** In eaches; null until entered. */
  eaches: number | null;
  /** Kept while this is on the screen, so sending it again moves nothing more. */
  requestId: string;
}

/**
 * Moving stock: scan an LPN, or a location and an item lying loose there, then where it goes. At a pack station, things can
 * be taken out of an LPN too.
 */
export function MoveTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const rf = useRf(onSignedOut);
  const [source, setSource] = useState<MoveSource | null>(null);
  /** Lines the scanned item could be, when there are several. */
  const [choices, setChoices] = useState<{ lines: MovableLine[]; scan: ItemScan } | null>(null);
  const [loose, setLoose] = useState<Loose | null>(null);
  const [over, setOver] = useState<{ to: string; message: string } | null>(null);
  /** An LPN at a pack station that is to be moved as it is. */
  const [whole, setWhole] = useState(false);
  const [putBack, setPutBack] = useState<PutBack[]>([]);

  // Looked at whenever there is nothing in hand.
  const idle = source === null;
  useEffect(() => {
    if (!idle) return;
    const controller = new AbortController();
    listPutBackOnDevice(controller.signal).then(
      (found) => Array.isArray(found) && setPutBack(found),
      () => undefined,
    );
    return () => controller.abort();
  }, [idle]);

  const reset = () => {
    setSource(null);
    setWhole(false);
    setChoices(null);
    setLoose(null);
    setOver(null);
  };

  const scanSource = (code: string) => rf.run(async () => setSource(await getMoveSource(code)));

  const choose = (line: MovableLine, scan: ItemScan) => {
    rf.clear();
    setChoices(null);
    setLoose({ line, scan, eaches: line.serialNumber ? 1 : null, requestId: newRequestId() });
  };

  const scanItem = (code: string) => {
    const found = source!.lines.filter((l) => l.scans.some((s) => scanMatches(code, s.value)));
    // Serial numbers can be scanned as they are.
    const serial = source!.lines.find((l) => l.serialNumber && scanMatches(code, l.serialNumber));
    const each = { value: '', uom: 'EA', eachesPerUnit: 1 };
    if (serial) return choose(serial, each);
    if (found.length === 0) return rf.problem(`${code.toUpperCase()} is not ${source!.lpn ? `in ${source!.lpn}` : `loose at ${source!.location}`}`);
    const scan = found[0].scans.find((s) => scanMatches(code, s.value))!;
    if (found.length === 1) return choose(found[0], scan);
    rf.clear();
    setChoices({ lines: found, scan });
  };

  const enterQuantity = (text: string) => {
    const units = /^[0-9]+$/.test(text) ? Number(text) : 0;
    const eaches = units * loose!.scan.eachesPerUnit;
    if (eaches < 1) return rf.problem('Enter a whole number');
    if (eaches > loose!.line.free) {
      return rf.problem(
        loose!.line.free === loose!.line.quantity ? `There are only ${loose!.line.free} EA` : `Only ${loose!.line.free} EA can be moved; the rest is reserved`,
      );
    }
    rf.clear();
    setLoose({ ...loose!, eaches });
  };

  const put = (to: string, allowOver = false) =>
    rf.run(
      async () => {
        const result = source!.lpn && !loose
          ? await moveLpn(source!.lpn, to, allowOver)
          : await moveLoose({ requestId: loose!.requestId, inventoryId: loose!.line.inventoryId, quantity: loose!.eaches!, toLocationId: to, allowOver });
        reset();
        rf.setDone(`${result.message}.`);
      },
      (e) => {
        if (e.status !== 409 || !e.fieldErrors.capacity) return false;
        setOver({ to, message: e.message });
        return true;
      },
    );

  const doneNote = rf.done && (
    <p className="rf-done" role="status">
      {rf.done}
    </p>
  );

  if (!source) {
    return (
      <>
        <h1 className="rf-title">Move</h1>
        {doneNote}
        <section className="rf-card">
          <p className="rf-card__action">Scan an LPN or a location</p>
          <p className="muted">An LPN is moved whole. At a location, choose what to move of what lies loose.</p>
        </section>
        <ScanInput label="LPN or location" busy={rf.busy} error={rf.error} onScan={(code) => void scanSource(code)} />
        {putBack.length > 0 && (
          <section aria-label="To put back">
            <h2 className="rf-card__action">To put back from pack stations</h2>
            <PutBackChoices places={putBack} busy={rf.busy} onChoose={(code) => void scanSource(code)} />
          </section>
        )}
      </>
    );
  }

  const strip = (
    <section className="rf-strip" aria-label="Moving">
      <div className="rf-strip__row">
        <h1 className="code">{source.lpn ?? source.location}</h1>
        <span>{source.containerType ?? 'loose stock'}</span>
      </div>
      {source.lpn && <span className="muted">at {source.location}</span>}
    </section>
  );
  const different = (
    <button
      type="button"
      className="rf-button rf-button--quiet"
      disabled={rf.busy}
      onClick={() => {
        rf.clear();
        reset();
      }}
    >
      Move something else
    </button>
  );

  if (over) {
    return (
      <>
        {strip}
        <section className="rf-card" role="group" aria-label="No room">
          <p className="rf-card__action">{over.message}</p>
          <div className="rf-choices">
            <button type="button" className="rf-button rf-button--danger" disabled={rf.busy} onClick={() => void put(over.to, true)}>
              Put it there anyway
            </button>
            <button type="button" className="rf-button rf-button--quiet" disabled={rf.busy} onClick={() => setOver(null)}>
              Scan a different location
            </button>
          </div>
        </section>
      </>
    );
  }

  if (choices) {
    return (
      <>
        {strip}
        <h2 className="rf-card__action">Which of these?</h2>
        <div className="rf-choices">
          {choices.lines.map((l) => (
            <button key={l.inventoryId} type="button" className="rf-button" onClick={() => choose(l, choices.scan)}>
              <strong className="code">{l.item.itemId}</strong> {l.quantity.toLocaleString()} EA
              <span className="muted cell__sub">{detail(l)}</span>
            </button>
          ))}
        </div>
        {different}
      </>
    );
  }

  if ((!source.lpn || source.byItem) && !loose && !whole) {
    if (source.lines.length === 0) {
      return (
        <>
          {strip}
          <h2 className="rf-card__action">What is put back from here?</h2>
          <PutBackChoices places={source.lpns} busy={rf.busy} onChoose={(code) => void scanSource(code)} />
          {different}
        </>
      );
    }
    const what = source.lpn ? 'To put back' : 'Loose here';
    return (
      <>
        {strip}
        <section className="rf-card" aria-label={what}>
          <p className="rf-card__step">{what}</p>
          <ul className="rf-list">
            {source.lines.map((l) => (
              <li key={l.inventoryId}>
                <span>
                  <span className="code">{l.item.itemId}</span>
                  <span className="muted cell__sub">{detail(l)}</span>
                </span>
                <span>{l.quantity.toLocaleString()} EA</span>
              </li>
            ))}
          </ul>
        </section>
        <ScanInput label="Item" busy={rf.busy} error={rf.error} onScan={scanItem} />
        {source.lpn && source.whole && (
          <button
            type="button"
            className="rf-button"
            disabled={rf.busy}
            onClick={() => {
              rf.clear();
              setWhole(true);
            }}
          >
            Move {source.lpn} as it is
          </button>
        )}
        {source.whyStays && <p className="muted">{source.whyStays}, so it can't be moved as it is.</p>}
        {source.lpns.length > 0 && <PutBackChoices places={source.lpns} busy={rf.busy} onChoose={(code) => void scanSource(code)} />}
        {different}
      </>
    );
  }

  if (loose && loose.eaches === null) {
    const units = Math.floor(loose.line.free / loose.scan.eachesPerUnit);
    return (
      <>
        {strip}
        <Moving line={loose.line} />
        <ScanInput
          label={`Quantity (${loose.scan.uom}${loose.scan.eachesPerUnit > 1 ? ` of ${loose.scan.eachesPerUnit} EA` : ''}), up to ${units.toLocaleString()}`}
          busy={rf.busy}
          error={rf.error}
          inputMode="numeric"
          onScan={enterQuantity}
        />
        {different}
      </>
    );
  }

  return (
    <>
      {strip}
      {loose ? (
        <Moving line={loose.line} eaches={loose.eaches!} />
      ) : (
        <section className="rf-card" aria-label="In it">
          <p className="rf-card__step">In it</p>
          <ul className="rf-list">
            {source.lines.slice(0, 5).map((l) => (
              <li key={l.inventoryId}>
                <span className="code">{l.item.itemId}</span>
                <span>{l.quantity.toLocaleString()} EA</span>
              </li>
            ))}
          </ul>
          {source.lines.length > 5 && <p className="muted">and {source.lines.length - 5} more</p>}
        </section>
      )}
      <ScanInput label="Move to location" busy={rf.busy} error={rf.error} onScan={(code) => void put(code)} />
      {different}
    </>
  );
}

function PutBackChoices({ places, busy, onChoose }: { places: PutBack[]; busy: boolean; onChoose: (code: string) => void }) {
  return (
    <div className="rf-choices">
      {places.map((p) => (
        <button key={`${p.location} ${p.lpn ?? ''}`} type="button" className="rf-button" disabled={busy} onClick={() => onChoose(p.lpn ?? p.location)}>
          <strong className="code">{p.lpn ?? p.location}</strong> {p.quantity.toLocaleString()} EA
          <span className="muted cell__sub">
            {[p.lpn ? `at ${p.location}` : 'loose', itemsOf(p), p.held > 0 && `${p.held.toLocaleString()} on hold`].filter(Boolean).join(' · ')}
          </span>
        </button>
      ))}
    </div>
  );
}

function Moving({ line, eaches }: { line: MovableLine; eaches?: number }) {
  return (
    <section className="rf-card" aria-label="What moves">
      <p className="rf-card__big code">{eaches === undefined ? line.item.itemId : `${eaches.toLocaleString()} EA`}</p>
      <p>{eaches === undefined ? line.item.description : `${line.item.itemId} · ${line.item.description}`}</p>
      <p className="muted">{detail(line)}</p>
    </section>
  );
}

function detail(l: MovableLine): string {
  return [
    l.lotNumber && `lot ${l.lotNumber}`,
    l.serialNumber,
    l.expiryDate && `expires ${formatDay(l.expiryDate)}`,
    l.status !== 'AVAILABLE' && inventoryStatusLabel(l.status),
    l.free < l.quantity && `${(l.quantity - l.free).toLocaleString()} reserved`,
  ]
    .filter(Boolean)
    .join(' · ');
}
