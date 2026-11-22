import { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { formatUnits, scanMatches, type ItemScan, type PickConfirmation, type PickDirective } from '../../api/rf';
import { formatDay } from '../outbound/OrdersPage';
import { ScanInput } from './ScanInput';

type Part = 'location' | 'lpn' | 'item' | 'serial' | 'quantity';

interface PickStepProps {
  pick: PickDirective;
  toLpnId: string;
  busy: boolean;
  /** What the server said about the last confirm. */
  error: string | null;
  onClearError: () => void;
  onConfirm: (confirmation: PickConfirmation, summary: string) => void;
}

/**
 * One task detail, a scan at a time: location, container (if the stock is in one), item,
 * serial number (if tracked), then quantity. Each scan is checked here for a quick answer,
 * and all of them again by the server when the quantity is confirmed.
 */
export function PickStep({ pick, toLpnId, busy, error, onClearError, onConfirm }: PickStepProps) {
  const [location, setLocation] = useState<string | null>(null);
  const [lpn, setLpn] = useState<string | null>(null);
  const [item, setItem] = useState<ItemScan | null>(null);
  const [serial, setSerial] = useState<string | null>(null);
  const [scanError, setScanError] = useState<string | null>(null);
  /** A quantity below the directed one, waiting for the picker to say why. */
  const [less, setLess] = useState<number | null>(null);
  const [reportingShort, setReportingShort] = useState(false);

  // Part of the pick went in: the place is still right, but the next unit has to be scanned.
  useEffect(() => {
    setItem(null);
    setSerial(null);
    setLess(null);
  }, [pick.pickedQuantity]);

  const part: Part = !location
    ? 'location'
    : pick.fromLpnId && !lpn
      ? 'lpn'
      : !item
        ? 'item'
        : pick.serialNumber && !serial
          ? 'serial'
          : 'quantity';

  const expected = item ? Math.floor(pick.remaining / item.eachesPerUnit) : 0;

  const attempt = (problem: string | null, accept: () => void) => {
    onClearError();
    setScanError(null);
    if (problem) {
      // Let the box see a change even when the same mistake is made twice.
      setTimeout(() => setScanError(problem));
    } else {
      accept();
    }
  };

  const scan = (value: string) => {
    switch (part) {
      case 'location':
        return attempt(scanMatches(value, pick.locationId) ? null : `Wrong location. Go to ${pick.locationId}`, () => setLocation(value));
      case 'lpn':
        return attempt(scanMatches(value, pick.fromLpnId!) ? null : `Wrong container. Pick from ${pick.fromLpnId}`, () => setLpn(value));
      case 'item': {
        const found = pick.itemScans.find((s) => scanMatches(value, s.value));
        const problem = !found
          ? `Wrong item. Pick ${pick.item.itemId}`
          : found.eachesPerUnit > pick.remaining
            ? `One ${found.uom} is ${found.eachesPerUnit} EA, but only ${pick.remaining} EA are left. Scan a smaller unit`
            : null;
        return attempt(problem, () => setItem(found!));
      }
      case 'serial':
        return attempt(scanMatches(value, pick.serialNumber!) ? null : `Wrong serial number. Pick ${pick.serialNumber}`, () => setSerial(value));
      case 'quantity': {
        const quantity = /^[0-9]+$/.test(value) ? Number(value) : NaN;
        const problem =
          Number.isNaN(quantity) || quantity < 1
            ? 'Enter a whole number'
            : quantity > expected
              ? `Too many: pick ${expected} ${item!.uom}`
              : null;
        return attempt(problem, () => (quantity < expected ? setLess(quantity) : confirm(quantity, false)));
      }
    }
  };

  const confirm = (quantity: number, shortPick: boolean) => {
    const picked = quantity > 0 ? `Picked ${quantity} ${item!.uom} of ${pick.item.itemId}` : `Nothing picked of ${pick.item.itemId}`;
    onConfirm(
      {
        detailId: pick.detailId,
        pickedSoFar: pick.pickedQuantity,
        locationId: location!,
        fromLpnId: lpn,
        itemBarcode: quantity > 0 ? item!.value : null,
        serialNumber: quantity > 0 ? serial : null,
        quantity,
        shortPick,
      },
      shortPick ? `${picked}. The rest is reported short.` : picked,
    );
  };

  const askingWhy = less !== null && item !== null;
  const shownError = scanError ?? error;

  return (
    <>
      <section className="rf-card">
        {part === 'location' && (
          <>
            <p className="rf-card__step">Go to location</p>
            <p className="rf-card__big code">{pick.locationId}</p>
          </>
        )}
        {part === 'lpn' && (
          <>
            <p className="rf-card__step">Pick from container</p>
            <p className="rf-card__big code">{pick.fromLpnId}</p>
          </>
        )}
        {(part === 'item' || part === 'serial' || part === 'quantity') && (
          <>
            <p className="rf-card__step">{part === 'serial' ? 'Pick serial number' : 'Pick'}</p>
            <p className="rf-card__big code">{part === 'serial' ? pick.serialNumber : formatUnits(pick.directed)}</p>
          </>
        )}
        <dl className="rf-facts">
          {part !== 'location' && <Fact label="Location" value={pick.locationId} done />}
          {pick.fromLpnId && part !== 'lpn' && <Fact label="From" value={pick.fromLpnId} done={lpn !== null} />}
          <Fact label="Item" value={pick.item.itemId} detail={pick.item.description} done={item !== null} />
          {pick.lotNumber && <Fact label="Lot" value={pick.lotNumber} detail={pick.expiryDate ? `expires ${formatDay(pick.expiryDate)}` : undefined} />}
          {pick.serialNumber && part !== 'serial' && <Fact label="Serial" value={pick.serialNumber} done={serial !== null} />}
          <Fact
            label="Quantity"
            value={`${pick.remaining.toLocaleString()} EA`}
            detail={pick.pickedQuantity > 0 ? `${pick.pickedQuantity} of ${pick.quantity} picked` : undefined}
          />
          <Fact label="Order" value={pick.orderNumber} detail={pick.customerName} />
        </dl>
      </section>

      {askingWhy ? (
        <section className="rf-card" role="group" aria-label="Less than directed">
          <p className="rf-card__action">
            {less} of {expected} {item.uom}
          </p>
          {error && (
            <p className="rf-error" role="alert">
              {error}
            </p>
          )}
          <div className="rf-choices">
            <button type="button" className="rf-button rf-button--primary" disabled={busy} onClick={() => confirm(less, false)}>
              Keep picking the rest
            </button>
            <button type="button" className="rf-button rf-button--danger" disabled={busy} onClick={() => confirm(less, true)}>
              Short: there is no more here
            </button>
            <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => setLess(null)}>
              Change quantity
            </button>
          </div>
        </section>
      ) : reportingShort ? (
        <section className="rf-card" role="group" aria-label="Report short">
          <p className="rf-card__action">Nothing left to pick here?</p>
          <p className="muted">{pick.remaining} EA will be put on hold to be counted.</p>
          {error && (
            <p className="rf-error" role="alert">
              {error}
            </p>
          )}
          <div className="rf-choices">
            <button type="button" className="rf-button rf-button--danger" disabled={busy} onClick={() => confirm(0, true)}>
              Report short
            </button>
            <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => setReportingShort(false)}>
              Go back
            </button>
          </div>
        </section>
      ) : (
        <>
          <ScanInput
            key={part}
            label={LABELS[part](item, expected, toLpnId)}
            busy={busy}
            error={shownError}
            inputMode={part === 'quantity' ? 'numeric' : undefined}
            onScan={scan}
          />
          {part !== 'location' && part !== 'quantity' && (
            <button
              type="button"
              className="rf-button rf-button--quiet"
              disabled={busy}
              onClick={() => {
                onClearError();
                setScanError(null);
                setReportingShort(true);
              }}
            >
              Can't find it
            </button>
          )}
          {part === 'quantity' && (
            <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => attempt(null, () => setItem(null))}>
              Scan a different unit
            </button>
          )}
        </>
      )}
    </>
  );
}

const LABELS: Record<Part, (item: ItemScan | null, expected: number, toLpnId: string) => string> = {
  location: () => 'Scan location',
  lpn: () => 'Scan container',
  item: () => 'Scan item',
  serial: () => 'Scan serial number',
  quantity: (item, expected, toLpnId) => `Put ${expected} ${item!.uom} in ${toLpnId}. Confirm quantity (${item!.uom})`,
};

function Fact({ label, value, detail, done = false }: { label: string; value: string; detail?: string; done?: boolean }) {
  return (
    <div className="rf-fact">
      <dt>{label}</dt>
      <dd>
        <span className="code">{value}</span>
        {done && <Check size={16} className="rf-fact__done" aria-label="Scanned" />}
        {detail && <span className="muted rf-fact__detail">{detail}</span>}
      </dd>
    </div>
  );
}
