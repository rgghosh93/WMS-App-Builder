import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Printer } from 'lucide-react';
import { errorMessage } from '../../api/http';
import { getManifest, shipmentStatusLabel, shippingBadge, type Manifest } from '../../api/shipping';
import { formatDateTime } from '../outbound/OrdersPage';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; manifest: Manifest };

/** One manifest and the shipments on it. */
export function ManifestDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [state, setState] = useState<LoadState>({ status: 'loading' });

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    getManifest(id, controller.signal)
      .then((manifest) => setState({ status: 'ready', manifest }))
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
          <Link to="/pack-ship/manifests">Back to manifests</Link>
        </p>
      </div>
    );
  }

  const m = state.manifest;
  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/pack-ship/manifests">Manifests</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{m.manifestNumber}</span>
      </nav>

      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{m.manifestNumber}</span>
            <span className={`badge badge--lg ${shippingBadge(m.status)}`}>{m.status === 'OPEN' ? 'Open' : 'Closed'}</span>
          </h1>
          <p className="muted">
            {m.status === 'OPEN' ? 'Being loaded. Closing it on the device ships everything on it.' : `Shipped ${formatDateTime(m.closedAt!)}`}
          </p>
        </div>
        <Link className="button" to={`/print/manifests/${m.id}`} target="_blank">
          <Printer size={16} aria-hidden="true" />
          Print manifest
        </Link>
      </header>

      <dl className="facts">
        <div>
          <dt>Carrier</dt>
          <dd>{m.carrier.name}</dd>
        </div>
        <div>
          <dt>Dock door</dt>
          <dd className="code">{m.door.locationId}</dd>
        </div>
        <div>
          <dt>Trailer</dt>
          <dd>{m.trailerNumber ? <span className="code">{m.trailerNumber}</span> : <span className="muted">—</span>}</dd>
        </div>
        <div>
          <dt>Seal</dt>
          <dd>{m.sealNumber ? <span className="code">{m.sealNumber}</span> : <span className="muted">—</span>}</dd>
        </div>
        <div>
          <dt>Loaded</dt>
          <dd>
            {m.cartonCount} carton{m.cartonCount === 1 ? '' : 's'} · {m.totalQuantity.toLocaleString()} EA
          </dd>
        </div>
        <div>
          <dt>Opened</dt>
          <dd>
            {formatDateTime(m.createdAt)}
            <span className="muted cell__sub">{m.createdBy}</span>
          </dd>
        </div>
        {m.closedAt && (
          <div>
            <dt>Closed</dt>
            <dd>
              {formatDateTime(m.closedAt)}
              <span className="muted cell__sub">{m.closedBy}</span>
            </dd>
          </div>
        )}
      </dl>

      <section className="section" aria-labelledby="manifest-shipments-title">
        <h2 id="manifest-shipments-title">Shipments</h2>
        {m.shipments.length === 0 ? (
          <p className="muted">Nothing loaded yet.</p>
        ) : (
          <div className="table-wrap">
            <table className="table" aria-label="Shipments on the manifest">
              <thead>
                <tr>
                  <th scope="col">Shipment</th>
                  <th scope="col">Order</th>
                  <th scope="col">Ship to</th>
                  <th scope="col">Service</th>
                  <th scope="col">Cartons</th>
                  <th scope="col" className="num">
                    Quantity
                  </th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {m.shipments.map((s) => (
                  <tr key={s.id}>
                    <th scope="row">
                      <Link className="code" to={`/pack-ship/shipments/${s.id}`}>
                        {s.shipmentNumber}
                      </Link>
                    </th>
                    <td>
                      <Link className="code" to={`/outbound/orders/${s.order.id}`}>
                        {s.order.orderNumber}
                      </Link>
                      <span className="muted cell__sub">{s.order.customerName}</span>
                    </td>
                    <td>{s.shipTo && `${s.shipTo.city}${s.shipTo.state ? `, ${s.shipTo.state}` : ''}`}</td>
                    <td>{s.service?.name}</td>
                    <td>
                      {s.cartons.map((c) => (
                        <span key={c.id} className="cell__sub ship-line">
                          <span className="code">{c.container.lpnId}</span>
                          {c.trackingNumber && <span className="muted"> · {c.trackingNumber}</span>}
                          {!c.loadedAt && <span className="muted"> · not loaded</span>}
                        </span>
                      ))}
                    </td>
                    <td className="num">{s.totalQuantity.toLocaleString()} EA</td>
                    <td>
                      <span className={`badge ${shippingBadge(s.status)}`}>{shipmentStatusLabel(s.status)}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
