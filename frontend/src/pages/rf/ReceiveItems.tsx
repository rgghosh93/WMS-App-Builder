import { useEffect, useState, type FormEvent } from 'react';
import { type AsnDetail, type AsnItem, type LpnCheck } from '../../api/asns';
import { INVENTORY_STATUSES, type InventoryStatus } from '../../api/inventory';
import { scanMatches, type ItemScan } from '../../api/rf';
import { formatDay } from '../outbound/OrdersPage';
import { ScanInput } from './ScanInput';

/** One receipt of an item, as captured. */
export interface ItemEntry {
  item: AsnItem;
  scan: ItemScan;
  /** In the unit of the scan. */
  quantity: number;
  lotNumber: string | null;
  expiryDate: string | null;
  serialNumbers: string[];
  condition: InventoryStatus;
}

interface ReceiveItemsProps {
  asn: AsnDetail;
  lpn: LpnCheck;
  busy: boolean;
  /** What the server said about the last receipt. */
  error: string | null;
  onClearError: () => void;
  onReceive: (entry: ItemEntry) => void;
}

type Part = 'item' | 'lot' | 'expiry' | 'serials' | 'quantity';

const CONDITIONS = INVENTORY_STATUSES.map((s) => (s.value === 'AVAILABLE' ? { ...s, label: 'Good' } : s));

/** One item at a time: scan it, then its lot, expiry date and serial numbers if it has them, then how many. */
export function ReceiveItems({ asn, lpn, busy, error, onClearError, onReceive }: ReceiveItemsProps) {
  const [item, setItem] = useState<AsnItem | null>(null);
  const [scan, setScan] = useState<ItemScan | null>(null);
  const [lot, setLot] = useState<string | null>(null);
  const [expiry, setExpiry] = useState<string | null>(null);
  const [expiryText, setExpiryText] = useState('');
  const [serials, setSerials] = useState<string[]>([]);
  const [condition, setCondition] = useState<InventoryStatus>('AVAILABLE');
  const [scanError, setScanError] = useState<string | null>(null);

  const reset = () => {
    setItem(null);
    setScan(null);
    setLot(null);
    setExpiry(null);
    setExpiryText('');
    setSerials([]);
    setCondition('AVAILABLE');
    setScanError(null);
  };

  // Something was received, so the next item is scanned.
  useEffect(reset, [asn.receipts.length]);

  const part: Part = !item
    ? 'item'
    : item.lotControlled && !lot
      ? 'lot'
      : item.expiryControlled && !expiry
        ? 'expiry'
        : item.serialControlled
          ? 'serials'
          : 'quantity';

  const lines = item ? asn.lines.filter((l) => l.item.id === item.id) : [];
  const remaining = lines.reduce((sum, l) => sum + l.remaining, 0);

  const problem = (message: string) => {
    onClearError();
    setScanError(null);
    // Let the box see a change even when the same mistake is made twice.
    setTimeout(() => setScanError(message));
  };
  const accept = (action: () => void) => {
    onClearError();
    setScanError(null);
    action();
  };

  const scanned = (value: string) => {
    if (part === 'item') {
      for (const line of asn.lines) {
        const found = line.item.scans.find((s) => scanMatches(value, s.value));
        if (found) {
          return accept(() => {
            setItem(line.item);
            // Serial numbers are one each, whatever was scanned.
            setScan(line.item.serialControlled ? (line.item.scans.find((s) => s.eachesPerUnit === 1) ?? found) : found);
          });
        }
      }
      return problem(`${value.toUpperCase()} is not on ${asn.asnNumber}`);
    }
    if (part === 'lot') {
      return accept(() => {
        const normal = value.toUpperCase();
        setLot(normal);
        setExpiryText(suggestedExpiry(item!, lines.find((l) => l.lotNumber === normal)?.expiryDate ?? null));
      });
    }
    if (part === 'serials') {
      const normal = value.toUpperCase();
      if (item!.scans.some((s) => scanMatches(value, s.value))) return problem('That is the item. Scan its serial number');
      if (serials.includes(normal)) return problem(`${normal} is already scanned`);
      return accept(() => setSerials((current) => [...current, normal]));
    }
    const quantity = /^[0-9]+$/.test(value) ? Number(value) : 0;
    if (quantity < 1) return problem('Enter a whole number');
    return accept(() => submit(quantity));
  };

  const submit = (quantity: number) =>
    onReceive({ item: item!, scan: scan!, quantity, lotNumber: lot, expiryDate: expiry, serialNumbers: serials, condition });

  // An item with an expiry date but no lot starts at the date the vendor gave, or today plus its shelf life.
  useEffect(() => {
    if (item && item.expiryControlled && !item.lotControlled) {
      setExpiryText(suggestedExpiry(item, asn.lines.find((l) => l.item.id === item.id && l.expiryDate)?.expiryDate ?? null));
    }
  }, [item, asn.lines]);

  const shownError = scanError ?? error;
  const errorNote = shownError && (
    <p className="rf-error" role="alert">
      {shownError}
    </p>
  );

  return (
    <div className="rf-choices">
      <section className="rf-card">
        {part === 'item' && (
          <>
            <p className="rf-card__action">Scan an item</p>
            {lpn.whyNotAll && <p className="muted">{lpn.whyNotAll}, so this LPN is received item by item.</p>}
            <p className="muted">Scan the label of the unit you are counting: each, case…</p>
          </>
        )}
        {item && scan && (
          <dl className="rf-facts rf-facts--top">
            <Fact label="Item" value={item.itemId} detail={item.description} />
            {lot && <Fact label="Lot" value={lot} />}
            {expiry && <Fact label="Expires" value={formatDay(expiry)} />}
            <Fact label="To come" value={`${remaining.toLocaleString()} EA`} />
          </dl>
        )}
      </section>

      {part === 'lot' && <LotChoices lots={[...new Set(lines.filter((l) => l.lotNumber && l.remaining > 0).map((l) => l.lotNumber!))]} busy={busy} onChoose={scanned} />}

      {part === 'expiry' ? (
        <form
          className="scan"
          onSubmit={(event: FormEvent) => {
            event.preventDefault();
            if (!expiryText) problem('Enter the expiry date');
            else accept(() => setExpiry(expiryText));
          }}
        >
          <label className="scan__label" htmlFor="rf-expiry">
            Expiry date
          </label>
          <input id="rf-expiry" type="date" className="scan__input" autoFocus value={expiryText} onChange={(e) => setExpiryText(e.target.value)} />
          {expiryText && <p className="muted">Check it against the label.</p>}
          {errorNote}
          <button type="submit" className="rf-button rf-button--primary" disabled={busy}>
            Use this date
          </button>
        </form>
      ) : (
        <>
          {(part === 'quantity' || part === 'serials') && (
            <div className="scan">
              <label className="scan__label" htmlFor="rf-condition">
                Condition
              </label>
              <select id="rf-condition" className="scan__input" value={condition} onChange={(e) => setCondition(e.target.value as InventoryStatus)}>
                {CONDITIONS.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </div>
          )}
          <ScanInput
            key={part}
            label={part === 'item' ? 'Item' : part === 'lot' ? 'Lot' : part === 'serials' ? `Serial number ${serials.length + 1}` : quantityLabel(scan!, remaining)}
            busy={busy}
            error={shownError}
            inputMode={part === 'quantity' ? 'numeric' : undefined}
            onScan={scanned}
          />
          {part === 'serials' && serials.length > 0 && (
            <>
              <section className="rf-card" aria-label="Serial numbers">
                <ul className="rf-list">
                  {serials.map((s) => (
                    <li key={s}>
                      <span className="code">{s}</span>
                      <button type="button" className="rf-link" aria-label={`Remove ${s}`} disabled={busy} onClick={() => accept(() => setSerials((current) => current.filter((x) => x !== s)))}>
                        Remove
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
              <button type="button" className="rf-button rf-button--primary" disabled={busy} onClick={() => accept(() => submit(serials.length))}>
                Receive {serials.length}
              </button>
            </>
          )}
        </>
      )}

      {item && (
        <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => accept(reset)}>
          Different item
        </button>
      )}
    </div>
  );
}

function LotChoices({ lots, busy, onChoose }: { lots: string[]; busy: boolean; onChoose: (lot: string) => void }) {
  return (
    <>
      {lots.map((lot) => (
        <button key={lot} type="button" className="rf-button" disabled={busy} onClick={() => onChoose(lot)}>
          Lot <strong className="code">{lot}</strong>
        </button>
      ))}
    </>
  );
}

function quantityLabel(scan: ItemScan, remaining: number): string {
  const units = Math.floor(remaining / scan.eachesPerUnit);
  const each = scan.eachesPerUnit > 1 ? ` of ${scan.eachesPerUnit} EA` : '';
  return `Quantity (${scan.uom}${each}), ${units.toLocaleString()} expected`;
}

/** What the vendor said, or today plus the item's shelf life. */
function suggestedExpiry(item: AsnItem, announced: string | null): string {
  if (announced) return announced;
  if (!item.shelfLifeDays) return '';
  const date = new Date();
  date.setDate(date.getDate() + item.shelfLifeDays);
  const two = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())}`;
}

function Fact({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <div className="rf-fact">
      <dt>{label}</dt>
      <dd>
        <span className="code">{value}</span>
        {detail && <span className="muted rf-fact__detail">{detail}</span>}
      </dd>
    </div>
  );
}
