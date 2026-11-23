import { useEffect, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { Printer } from 'lucide-react';
import { errorMessage } from '../../api/http';
import { WEIGHT_UOMS } from '../../api/measure';
import { addressLines, getManifest, getShipment, type Manifest, type Shipment } from '../../api/shipping';
import { Barcode } from '../../components/Barcode';
import { formatDateTime } from '../outbound/OrdersPage';

/** Loads what a document is about, then shows it with a Print button that stays off the paper. */
export function PrintPage<T>({ load, title, children }: { load: (id: string, signal: AbortSignal) => Promise<T>; title: string; children: (data: T) => ReactNode }) {
  const { id } = useParams<{ id: string }>();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    load(id, controller.signal)
      .then(setData)
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorMessage(e));
      });
    return () => controller.abort();
  }, [id, load]);

  return (
    <div className="doc">
      <div className="doc__bar">
        <strong>{title}</strong>
        <button type="button" className="button button--primary" disabled={!data} onClick={() => window.print()}>
          <Printer size={16} aria-hidden="true" />
          Print
        </button>
      </div>
      {error && (
        <p className="rf-error" role="alert">
          {error}
        </p>
      )}
      {!data && !error && <p className="muted">Loading…</p>}
      {data && children(data)}
    </div>
  );
}

/** One 4 x 6 inch label per carton. */
export function LabelsPage() {
  return (
    <PrintPage load={getShipment} title="Carton labels">
      {(s: Shipment) =>
        s.cartons.map((c, i) => (
          <article key={c.id} className="doc-label" aria-label={`Label ${c.container.lpnId}`}>
            <div className="doc-label__to">
              <span className="doc-label__tag">Ship to</span>
              {s.shipTo ? (
                addressLines(s.shipTo).map((line, n) => (
                  <span key={n} className={n === 0 ? 'doc-label__name' : undefined}>
                    {line}
                  </span>
                ))
              ) : (
                <span className="doc-label__name">No address yet</span>
              )}
            </div>
            <div className="doc-label__row">
              <div>
                <span className="doc-label__tag">Carrier</span>
                <strong>{s.carrier ? `${s.carrier.name} ${s.service?.name ?? ''}` : 'Not chosen'}</strong>
              </div>
              <div>
                <span className="doc-label__tag">Carton</span>
                <strong>
                  {i + 1} of {s.cartons.length}
                </strong>
              </div>
            </div>
            <div className="doc-label__row">
              <div>
                <span className="doc-label__tag">Order</span>
                <strong>{s.order.orderNumber}</strong>
              </div>
              <div>
                <span className="doc-label__tag">Shipment</span>
                <strong>{s.shipmentNumber}</strong>
              </div>
              <div>
                <span className="doc-label__tag">Weight</span>
                <strong>{c.weight === null ? '—' : `${c.weight} ${WEIGHT_UOMS.find((u) => u.value === c.weightUom)?.label ?? ''}`}</strong>
              </div>
            </div>
            {c.trackingNumber && (
              <p className="doc-label__tracking">
                Tracking <span className="code">{c.trackingNumber}</span>
              </p>
            )}
            <Barcode value={c.container.lpnId} height={80} />
          </article>
        ))
      }
    </PrintPage>
  );
}

/** What is in the shipment, for the customer. */
export function PackingSlipPage() {
  return (
    <PrintPage load={getShipment} title="Packing slip">
      {(s: Shipment) => {
        const lines = new Map<string, { line: number; itemId: string; description: string; lots: Set<string>; quantity: number }>();
        for (const c of s.cartons) {
          for (const l of c.contents) {
            const key = `${l.lineNumber}`;
            const row = lines.get(key) ?? { line: l.lineNumber, itemId: l.item.itemId, description: l.item.description, lots: new Set<string>(), quantity: 0 };
            const lot = l.serialNumber ?? l.lotNumber;
            if (lot) row.lots.add(lot);
            row.quantity += l.quantity;
            lines.set(key, row);
          }
        }
        return (
          <article className="doc-sheet">
            <header className="doc-sheet__head">
              <div>
                <h1>Packing slip</h1>
                <p>
                  Order <strong>{s.order.orderNumber}</strong> · Shipment <strong>{s.shipmentNumber}</strong>
                </p>
                <p>{s.shippedAt ? `Shipped ${formatDateTime(s.shippedAt)}` : 'Not shipped yet'}</p>
                {s.carrier && (
                  <p>
                    {s.carrier.name} {s.service?.name}
                  </p>
                )}
              </div>
              <address>
                <span className="doc-label__tag">Ship to</span>
                {s.shipTo ? addressLines(s.shipTo).map((line, i) => <span key={i}>{line}</span>) : <span>{s.order.customerName}</span>}
              </address>
            </header>
            <table className="doc-table">
              <thead>
                <tr>
                  <th scope="col">Line</th>
                  <th scope="col">Item</th>
                  <th scope="col">Description</th>
                  <th scope="col">Lot / serial</th>
                  <th scope="col" className="num">
                    Quantity
                  </th>
                </tr>
              </thead>
              <tbody>
                {[...lines.values()]
                  .sort((a, b) => a.line - b.line)
                  .map((l) => (
                    <tr key={l.line}>
                      <td>{l.line}</td>
                      <td className="code">{l.itemId}</td>
                      <td>{l.description}</td>
                      <td className="code">{[...l.lots].join(', ')}</td>
                      <td className="num">{l.quantity.toLocaleString()} EA</td>
                    </tr>
                  ))}
              </tbody>
            </table>
            <h2>Cartons</h2>
            <ul className="doc-sheet__cartons">
              {s.cartons.map((c) => (
                <li key={c.id}>
                  <span className="code">{c.container.lpnId}</span> · {c.totalQuantity.toLocaleString()} EA
                  {c.trackingNumber && ` · tracking ${c.trackingNumber}`}
                </li>
              ))}
            </ul>
            {s.unpackedQuantity > 0 && <p>{s.unpackedQuantity.toLocaleString()} EA of this order will follow separately.</p>}
          </article>
        );
      }}
    </PrintPage>
  );
}

/** What was loaded, for the driver to sign. */
export function ManifestPrintPage() {
  return (
    <PrintPage load={getManifest} title="Manifest">
      {(m: Manifest) => (
        <article className="doc-sheet">
          <header className="doc-sheet__head">
            <div>
              <h1>Manifest {m.manifestNumber}</h1>
              <p>
                {m.carrier.name} · door {m.door.locationId}
              </p>
              <p>
                Trailer {m.trailerNumber ?? '—'} · seal {m.sealNumber ?? '—'}
              </p>
              <p>{m.closedAt ? `Closed ${formatDateTime(m.closedAt)} by ${m.closedBy}` : 'Open: not shipped yet'}</p>
            </div>
            <Barcode value={m.manifestNumber} />
          </header>
          <table className="doc-table">
            <thead>
              <tr>
                <th scope="col">Shipment</th>
                <th scope="col">Order</th>
                <th scope="col">Ship to</th>
                <th scope="col">Carton</th>
                <th scope="col">Tracking</th>
                <th scope="col" className="num">
                  Weight
                </th>
              </tr>
            </thead>
            <tbody>
              {m.shipments.flatMap((s) =>
                s.cartons
                  .filter((c) => c.loadedAt)
                  .map((c) => (
                    <tr key={c.id}>
                      <td className="code">{s.shipmentNumber}</td>
                      <td className="code">{s.order.orderNumber}</td>
                      <td>{s.shipTo && `${s.shipTo.name}, ${s.shipTo.city}${s.shipTo.state ? ` ${s.shipTo.state}` : ''}`}</td>
                      <td className="code">{c.container.lpnId}</td>
                      <td className="code">{c.trackingNumber ?? '—'}</td>
                      <td className="num">
                        {c.weight === null ? '—' : `${c.weight} ${WEIGHT_UOMS.find((u) => u.value === c.weightUom)?.label ?? ''}`}
                      </td>
                    </tr>
                  )),
              )}
            </tbody>
          </table>
          <p>
            <strong>
              {m.shipments.length} shipment{m.shipments.length === 1 ? '' : 's'}, {m.cartonCount} carton{m.cartonCount === 1 ? '' : 's'}
            </strong>
          </p>
          <div className="doc-sheet__sign">
            <span>Driver signature</span>
            <span>Date and time</span>
          </div>
        </article>
      )}
    </PrintPage>
  );
}
