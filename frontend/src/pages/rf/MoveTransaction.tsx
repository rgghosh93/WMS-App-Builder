import { useState } from 'react';
import { getMoveSource, moveLoose, moveLpn, type MovableLine, type MoveSource } from '../../api/control';
import { inventoryStatusLabel } from '../../api/inventory';
import { scanMatches, type ItemScan } from '../../api/rf';
import { newRequestId } from '../../forms/requestId';
import { formatDay } from '../outbound/OrdersPage';
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

/** Moving stock: scan an LPN, or a location and an item lying loose there, then where it goes. */
export function MoveTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const rf = useRf(onSignedOut);
  const [source, setSource] = useState<MoveSource | null>(null);
  /** Lines the scanned item could be, when there are several. */
  const [choices, setChoices] = useState<{ lines: MovableLine[]; scan: ItemScan } | null>(null);
  const [loose, setLoose] = useState<Loose | null>(null);
  const [over, setOver] = useState<{ to: string; message: string } | null>(null);

  const reset = () => {
    setSource(null);
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
    if (found.length === 0) return rf.problem(`${code.toUpperCase()} is not loose at ${source!.location}`);
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
        const result = source!.lpn
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

  if (!source.lpn && !loose) {
    return (
      <>
        {strip}
        <section className="rf-card" aria-label="Loose here">
          <p className="rf-card__step">Loose here</p>
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
