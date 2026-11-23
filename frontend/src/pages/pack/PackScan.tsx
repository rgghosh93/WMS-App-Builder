import { useState } from 'react';
import type { Carton, PackDetail, PackScan as PackScanRequest, PackTask } from '../../api/packing';
import type { OrderRef } from '../../api/orders';
import { scanMatches, type ItemScan } from '../../api/rf';
import { ScanInput } from '../rf/ScanInput';

interface PackScanProps {
  task: PackTask;
  order: OrderRef;
  carton: Carton;
  busy: boolean;
  /** What the server said about the last scan. */
  error: string | null;
  onClearError: () => void;
  onPack: (scan: PackScanRequest, summary: string) => void;
}

/**
 * Packs one item into the open carton: scan it, say which lot if there are several, scan its
 * serial number if it has one, then confirm the quantity. A quantity of one needs no confirming.
 */
export function PackScan({ task, order, carton, busy, error, onClearError, onPack }: PackScanProps) {
  const [item, setItem] = useState<ItemScan | null>(null);
  const [wanted, setWanted] = useState<PackDetail[]>([]);
  const [lot, setLot] = useState<string | null>(null);
  const [scanError, setScanError] = useState<string | null>(null);

  const lots = [...new Set(wanted.map((d) => d.lotNumber).filter((l): l is string => l !== null))];
  const part = !item ? 'item' : wanted.some((d) => d.serialNumber) ? 'serial' : lots.length > 1 && !lot ? 'lot' : 'quantity';
  const chosen = lot ? wanted.filter((d) => d.lotNumber === lot) : wanted;
  const expected = item ? Math.floor(left(chosen) / item.eachesPerUnit) : 0;

  const fail = (message: string) => {
    onClearError();
    setScanError(null);
    // Let the box see a change even when the same mistake is made twice.
    setTimeout(() => setScanError(message));
  };

  const clear = () => {
    onClearError();
    setScanError(null);
  };

  const reset = () => {
    clear();
    setItem(null);
    setWanted([]);
    setLot(null);
  };

  const pack = (scan: ItemScan, details: PackDetail[], quantity: number, serialNumber: string | null, lotNumber: string | null) => {
    clear();
    onPack(
      { cartonId: carton.id, packedSoFar: task.packedQuantity, itemBarcode: scan.value, serialNumber, lotNumber, quantity },
      `Packed ${quantity} ${scan.uom} of ${details[0].item.itemId}`,
    );
  };

  /** Once the lot is known: ask how many, unless only one is possible. */
  const proceed = (scan: ItemScan, details: PackDetail[], lotNumber: string | null) => {
    const units = Math.floor(left(details) / scan.eachesPerUnit);
    if (units < 1) {
      fail(`One ${scan.uom} is ${scan.eachesPerUnit} EA, but only ${left(details)} EA are left. Scan a smaller unit`);
      setItem(null);
      setWanted([]);
      setLot(null);
      return;
    }
    if (units === 1) {
      pack(scan, details, 1, null, lotNumber);
      return;
    }
    clear();
    setItem(scan);
    setWanted(details);
    setLot(lotNumber);
  };

  const scanItem = (value: string) => {
    const matches = task.details.filter((d) => d.itemScans.some((s) => scanMatches(value, s.value)));
    if (matches.length === 0) return fail(`That item is not in ${task.container.lpnId}`);
    const itemId = matches[0].item.itemId;
    const scan = matches[0].itemScans.find((s) => scanMatches(value, s.value))!;
    const details = matches.filter((d) => d.order.id === order.id && d.remaining > 0);
    if (details.length === 0) {
      const other = matches.find((d) => d.remaining > 0);
      return fail(
        other
          ? `No more ${itemId} for ${order.orderNumber}. ${other.order.orderNumber} needs it`
          : `No more ${itemId} to pack`,
      );
    }
    const serials = details.some((d) => d.serialNumber);
    const severalLots = new Set(details.map((d) => d.lotNumber).filter(Boolean)).size > 1;
    if (serials || severalLots) {
      clear();
      setItem(scan);
      setWanted(details);
      return;
    }
    proceed(scan, details, null);
  };

  const scanSerial = (value: string) => {
    const match = wanted.find((d) => d.serialNumber && scanMatches(value, d.serialNumber));
    if (!match) return fail(`${value.toUpperCase()} is not to be packed here`);
    pack(item!, [match], 1, match.serialNumber, null);
  };

  const enterQuantity = (value: string) => {
    const quantity = /^[0-9]+$/.test(value) ? Number(value) : NaN;
    if (Number.isNaN(quantity) || quantity < 1) return fail('Enter a whole number');
    if (quantity > expected) return fail(`Too many: only ${expected} ${item!.uom} left to pack`);
    pack(item!, chosen, quantity, null, lot);
  };

  return (
    <section className="rf-card" aria-label="Pack items">
      <p className="rf-card__step">
        Into <span className="code">{carton.container.lpnId}</span> for <span className="code">{order.orderNumber}</span>
      </p>
      {part === 'item' && <ScanInput key="item" label="Scan item" busy={busy} error={scanError ?? error} onScan={scanItem} />}
      {part !== 'item' && <p className="rf-card__action">{wanted[0].item.itemId}</p>}
      {part === 'lot' && (
        <div className="rf-choices" role="group" aria-label="Which lot?">
          <span className="scan__label">Which lot?</span>
          {lots.map((l) => (
            <button
              key={l}
              type="button"
              className="rf-button"
              disabled={busy}
              onClick={() => proceed(item!, wanted.filter((d) => d.lotNumber === l), l)}
            >
              <span className="code">{l}</span> · {left(wanted.filter((d) => d.lotNumber === l))} EA left
            </button>
          ))}
        </div>
      )}
      {part === 'serial' && <ScanInput key="serial" label="Scan serial number" busy={busy} error={scanError ?? error} onScan={scanSerial} />}
      {part === 'quantity' && (
        <ScanInput
          key="quantity"
          label={`How many ${item!.uom}? Up to ${expected}`}
          busy={busy}
          error={scanError ?? error}
          inputMode="numeric"
          onScan={enterQuantity}
        />
      )}
      {part !== 'item' && (
        <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={reset}>
          Scan a different item
        </button>
      )}
    </section>
  );
}

function left(details: PackDetail[]): number {
  return details.reduce((sum, d) => sum + d.remaining, 0);
}
