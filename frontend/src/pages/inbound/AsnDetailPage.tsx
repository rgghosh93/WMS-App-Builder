import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Printer, XCircle } from 'lucide-react';
import { asnStatusLabel, cancelAsn, getAsn, type AsnDetail, type AsnLine } from '../../api/asns';
import { errorMessage } from '../../api/http';
import { inventoryStatusLabel } from '../../api/inventory';
import { statusClass } from '../../api/orders';
import { useCan } from '../../session/SessionContext';
import { formatDateTime, formatDay } from '../outbound/OrdersPage';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; asn: AsnDetail };

/** One ASN: what is expected, what has been received into which LPNs, and what has happened. */
export function AsnDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [confirming, setConfirming] = useState(false);
  const may = useCan('INBOUND');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    getAsn(id, controller.signal)
      .then((asn) => setState({ status: 'ready', asn }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [id]);

  if (state.status === 'loading') return <p className="muted page">Loading…</p>;
  if (state.status === 'error') {
    return (
      <div className="page">
        <div className="notice" role="alert">
          <p>{state.message}</p>
        </div>
        <p>
          <Link to="/inbound/asns">Back to ASNs</Link>
        </p>
      </div>
    );
  }

  const asn = state.asn;
  const closed = asn.status === 'RECEIVED';
  const canCancel = may && (asn.status === 'EXPECTED' || asn.status === 'RECEIVING') && asn.receivedQuantity === 0;
  const anyLpn = asn.lines.some((l) => l.lpnId);
  const lpnCount = new Set(asn.receipts.filter((r) => !r.reversedAt).map((r) => r.lpn.id)).size;

  const cancel = async () => {
    setBusy(true);
    setActionError(null);
    try {
      setState({ status: 'ready', asn: await cancelAsn(asn.id) });
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/inbound/asns">ASNs</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{asn.asnNumber}</span>
      </nav>

      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{asn.asnNumber}</span>
            <span className={`badge badge--lg badge--asn-${statusClass(asn.status)}`}>{asnStatusLabel(asn.status)}</span>
          </h1>
          <p className="muted">{asn.vendorName}</p>
        </div>
        <div className="button-row">
          {lpnCount > 0 && (
            <a className="button" href={`/print/asns/${asn.id}/lpn-labels`} target="_blank" rel="noreferrer">
              <Printer size={16} aria-hidden="true" />
              LPN labels
            </a>
          )}
          {canCancel && !confirming && (
            <button type="button" className="button button--danger-quiet" onClick={() => setConfirming(true)}>
              <XCircle size={16} aria-hidden="true" />
              Cancel ASN
            </button>
          )}
        </div>
      </header>

      {confirming && (
        <div className="confirm-bar" role="group" aria-label="Confirm cancel">
          <span>Cancel {asn.asnNumber}? It can no longer be received.</span>
          <div className="button-row">
            <button type="button" className="button" onClick={() => setConfirming(false)} disabled={busy}>
              Keep ASN
            </button>
            <button type="button" className="button button--danger" onClick={cancel} disabled={busy}>
              Cancel ASN
            </button>
          </div>
        </div>
      )}
      {actionError && (
        <div className="form__error" role="alert">
          {actionError}
        </div>
      )}

      <dl className="facts">
        <div>
          <dt>Vendor</dt>
          <dd>{asn.vendorName}</dd>
        </div>
        <div>
          <dt>Reference</dt>
          <dd>{asn.reference ?? <span className="muted">—</span>}</dd>
        </div>
        <div>
          <dt>Expected</dt>
          <dd>{asn.expectedDate ? formatDay(asn.expectedDate) : <span className="muted">—</span>}</dd>
        </div>
        <div>
          <dt>Received</dt>
          <dd>
            {asn.receivedQuantity.toLocaleString()} of {asn.expectedQuantity.toLocaleString()} EA
          </dd>
        </div>
        <div>
          <dt>LPNs</dt>
          <dd>{lpnCount}</dd>
        </div>
        {asn.closedAt && (
          <div>
            <dt>{closed ? 'Closed' : 'Cancelled'}</dt>
            <dd>
              {formatDateTime(asn.closedAt)}
              {asn.closedBy && <span className="muted cell__sub">by {asn.closedBy}</span>}
            </dd>
          </div>
        )}
      </dl>

      <section className="section" aria-labelledby="asn-lines-title">
        <h2 id="asn-lines-title">Lines</h2>
        <div className="table-wrap">
          <table className="table" aria-label="Lines">
            <thead>
              <tr>
                <th scope="col" className="num">
                  Line
                </th>
                <th scope="col">Item</th>
                {anyLpn && <th scope="col">Vendor's LPN</th>}
                <th scope="col">Lot / expiry</th>
                <th scope="col" className="num">
                  Expected
                </th>
                <th scope="col" className="num">
                  Received
                </th>
                <th scope="col" className="num">
                  Difference
                </th>
              </tr>
            </thead>
            <tbody>
              {asn.lines.map((l) => (
                <tr key={l.id}>
                  <td className="num">{l.lineNumber}</td>
                  <td>
                    <span className="code">{l.item.itemId}</span>
                    <span className="muted cell__sub">{l.item.description}</span>
                  </td>
                  {anyLpn && <td className="code">{l.lpnId ?? <span className="muted">—</span>}</td>}
                  <td>
                    {l.lotNumber ? <span className="code">{l.lotNumber}</span> : <span className="muted">—</span>}
                    {l.expiryDate && <span className="muted cell__sub">expires {formatDay(l.expiryDate)}</span>}
                  </td>
                  <td className="num">{l.expectedQuantity.toLocaleString()} EA</td>
                  <td className="num">{l.receivedQuantity.toLocaleString()} EA</td>
                  <td className="num">
                    <Difference line={l} closed={closed} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="section" aria-labelledby="asn-receipts-title">
        <h2 id="asn-receipts-title">Receipts</h2>
        {asn.receipts.length === 0 ? (
          <p className="muted">
            Nothing received yet. Receive it under <Link to="/rf/receiving">Receiving</Link> on a mobile device.
          </p>
        ) : (
          <div className="table-wrap">
            <table className="table" aria-label="Receipts">
              <thead>
                <tr>
                  <th scope="col">LPN</th>
                  <th scope="col">Item</th>
                  <th scope="col">Lot / serial</th>
                  <th scope="col" className="num">
                    Quantity
                  </th>
                  <th scope="col">Condition</th>
                  <th scope="col">Received at</th>
                  <th scope="col">By</th>
                </tr>
              </thead>
              <tbody>
                {asn.receipts.map((r) => (
                  <tr key={r.id} className={r.reversedAt ? 'receipt--reversed' : undefined}>
                    <th scope="row">
                      <Link className="code" to={`/inventory/by-container?container=${r.lpn.id}`}>
                        {r.lpn.lpnId}
                      </Link>
                      <span className="muted cell__sub">{r.lpn.containerType}</span>
                    </th>
                    <td>
                      <span className="code">{r.itemId}</span>
                      <span className="muted cell__sub">
                        line {r.lineNumber} · {r.description}
                      </span>
                    </td>
                    <td>
                      <span className="code">{[r.lotNumber, r.serialNumber].filter(Boolean).join(' / ') || '—'}</span>
                      {r.expiryDate && <span className="muted cell__sub">expires {formatDay(r.expiryDate)}</span>}
                    </td>
                    <td className="num">{r.quantity.toLocaleString()} EA</td>
                    <td>
                      {r.reversedAt ? (
                        <span className="badge badge--inactive">Reversed</span>
                      ) : (
                        <span className={`badge badge--inv-${statusClass(r.condition)}`}>{inventoryStatusLabel(r.condition)}</span>
                      )}
                    </td>
                    <td>
                      <Link className="code" to={`/inventory/by-location?location=${r.location.id}`}>
                        {r.location.locationId}
                      </Link>
                      <span className="muted cell__sub">{formatDateTime(r.receivedAt)}</span>
                    </td>
                    <td>
                      {r.receivedBy}
                      {r.reversedBy && <span className="muted cell__sub">reversed by {r.reversedBy}</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="section" aria-labelledby="asn-activity-title">
        <h2 id="asn-activity-title">Activity</h2>
        <ol className="timeline">
          {asn.events.map((e, i) => (
            <li key={i} className={`timeline__item timeline__item--asn-${statusClass(e.type)}`}>
              <span className="timeline__time">{formatDateTime(e.occurredAt)}</span>
              <span className="timeline__message">{e.message}</span>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}

/** What is still to come, or once closed, what never came; or how much came over. */
function Difference({ line, closed }: { line: AsnLine; closed: boolean }) {
  const over = line.receivedQuantity - line.expectedQuantity;
  if (over > 0) return <strong>{over.toLocaleString()} over</strong>;
  if (line.remaining === 0) return <span className="muted">—</span>;
  return closed ? <strong>{line.remaining.toLocaleString()} short</strong> : <span className="muted">{line.remaining.toLocaleString()} to come</span>;
}
