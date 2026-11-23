import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Printer } from 'lucide-react';
import { listCarriers, type Carrier } from '../../api/carriers';
import { ApiError, errorMessage } from '../../api/http';
import { orderStatusLabel, statusClass } from '../../api/orders';
import type { Carton } from '../../api/packing';
import {
  addressLines,
  getShipment,
  setShipmentService,
  setTracking,
  shipmentStatusLabel,
  shippingBadge,
  type Shipment,
} from '../../api/shipping';
import { useCan } from '../../session/SessionContext';
import { formatDateTime } from '../outbound/OrdersPage';
import { CartonCard } from '../packing/CartonCard';
import { AddressForm } from './AddressForm';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; shipment: Shipment };

/** One shipment: what it needs before it can be loaded, its cartons and their tracking numbers. */
export function ShipmentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [carriers, setCarriers] = useState<Carrier[]>([]);
  const [editingAddress, setEditingAddress] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const may = useCan('SHIPPING');

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    setState({ status: 'loading' });
    getShipment(id, controller.signal)
      .then((shipment) => setState({ status: 'ready', shipment }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [id]);

  useEffect(() => {
    const controller = new AbortController();
    listCarriers(controller.signal)
      .then(setCarriers)
      .catch((error) => {
        if (!controller.signal.aborted) setActionError(errorMessage(error));
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!notice) return;
    const handle = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(handle);
  }, [notice]);

  if (state.status === 'loading') return <p className="muted page">Loading…</p>;
  if (state.status === 'error') {
    return (
      <div className="page">
        <div className="notice" role="alert">
          <p>{state.message}</p>
        </div>
        <p>
          <Link to="/pack-ship/shipments">Back to shipments</Link>
        </p>
      </div>
    );
  }

  const shipment = state.shipment;
  const open = shipment.status === 'OPEN';
  const show = (next: Shipment) => setState({ status: 'ready', shipment: next });

  /** Runs one change. A conflict means someone else changed it, so it is reloaded. */
  const run = async (action: () => Promise<Shipment>, message: string) => {
    setBusy(true);
    setActionError(null);
    try {
      show(await action());
      setNotice(message);
    } catch (error) {
      setActionError(errorMessage(error));
      if (error instanceof ApiError && error.status === 409) {
        try {
          show(await getShipment(shipment.id));
        } catch {
          // Keep showing what we had; the message says what went wrong.
        }
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <Link to="/pack-ship/shipments">Shipments</Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{shipment.shipmentNumber}</span>
      </nav>

      <header className="page__header">
        <div>
          <h1 className="order-title">
            <span className="code">{shipment.shipmentNumber}</span>
            <span className={`badge badge--lg ${shippingBadge(shipment.status)}`}>{shipmentStatusLabel(shipment.status)}</span>
          </h1>
          <p className="muted">
            {shipment.status === 'SHIPPED'
              ? `Shipped ${formatDateTime(shipment.shippedAt!)}`
              : shipment.status === 'LOADING'
                ? `${shipment.loadedCount} of ${shipment.cartons.length} cartons loaded`
                : shipment.missing.length === 0
                  ? 'Ready to load at a dock door'
                  : 'Not ready to load yet'}
          </p>
        </div>
        <div className="button-row">
          <Link className="button" to={`/print/shipments/${shipment.id}/packing-slip`} target="_blank">
            <Printer size={16} aria-hidden="true" />
            Packing slip
          </Link>
          <Link className="button" to={`/print/shipments/${shipment.id}/labels`} target="_blank">
            <Printer size={16} aria-hidden="true" />
            Carton labels
          </Link>
        </div>
      </header>

      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
      {actionError && (
        <div className="form__error" role="alert">
          {actionError}
        </div>
      )}
      {open && shipment.missing.length > 0 && (
        <div className="notice-info" role="group" aria-label="Still needed">
          <strong>Still needed before loading</strong>
          <ul className="ship-needed">
            {shipment.missing.map((m) => (
              <li key={m}>{m}</li>
            ))}
          </ul>
        </div>
      )}
      {shipment.unpackedQuantity > 0 && (
        <p className="notice-info">
          {shipment.unpackedQuantity.toLocaleString()} EA of {shipment.order.orderNumber} are not packed, so they won't go with this shipment.
        </p>
      )}

      <dl className="facts">
        <div>
          <dt>Order</dt>
          <dd>
            <Link className="code" to={`/outbound/orders/${shipment.order.id}`}>
              {shipment.order.orderNumber}
            </Link>{' '}
            <span className={`badge badge--sm badge--order-${statusClass(shipment.order.status)}`}>
              {orderStatusLabel(shipment.order.status)}
            </span>
          </dd>
        </div>
        <div>
          <dt>Customer</dt>
          <dd>{shipment.order.customerName}</dd>
        </div>
        <div>
          <dt>Quantity</dt>
          <dd>
            {shipment.totalQuantity.toLocaleString()} EA in {shipment.cartons.length} carton{shipment.cartons.length === 1 ? '' : 's'}
          </dd>
        </div>
        <div>
          <dt>Manifest</dt>
          <dd>
            {shipment.manifest ? (
              <Link className="code" to={`/pack-ship/manifests/${shipment.manifest.id}`}>
                {shipment.manifest.manifestNumber}
              </Link>
            ) : (
              <span className="muted">—</span>
            )}
          </dd>
        </div>
        <div className="facts__wide">
          <dt>Ship to</dt>
          <dd>
            {shipment.shipTo ? (
              <address className="ship-address">
                {addressLines(shipment.shipTo).map((line, i) => (
                  <span key={i}>{line}</span>
                ))}
              </address>
            ) : (
              <span className="muted">No address yet</span>
            )}{' '}
            {open && may && (
              <button type="button" className="link-button link-button--small" onClick={() => setEditingAddress(true)}>
                {shipment.shipTo ? 'Change address' : 'Enter address'}
              </button>
            )}
          </dd>
        </div>
        <div className="facts__wide">
          <dt>
            <label htmlFor="shipment-service">Carrier service</label>
          </dt>
          <dd>
            {open && may ? (
              <select
                id="shipment-service"
                value={shipment.service?.id ?? ''}
                disabled={busy}
                onChange={(e) =>
                  run(() => setShipmentService(shipment.id, e.target.value || null, shipment.version), 'Carrier service saved')
                }
              >
                <option value="">Choose…</option>
                {carriers.map((c) => {
                  const choices = c.services.filter(
                    (s) => s.id === shipment.service?.id || (c.status === 'ACTIVE' && s.status === 'ACTIVE'),
                  );
                  return choices.length === 0 ? null : (
                    <optgroup key={c.id} label={c.name}>
                      {choices.map((s) => (
                        <option key={s.id} value={s.id}>
                          {c.name} · {s.name}
                        </option>
                      ))}
                    </optgroup>
                  );
                })}
              </select>
            ) : (
              (shipment.service ? `${shipment.carrier?.name ?? ''} · ${shipment.service.name}` : <span className="muted">Not chosen</span>)
            )}
          </dd>
        </div>
      </dl>

      <section className="section" aria-labelledby="shipment-cartons-title">
        <h2 id="shipment-cartons-title">Cartons</h2>
        <div className="carton-list">
          {shipment.cartons.map((c) => (
            <div key={c.id} className="ship-carton">
              <CartonCard carton={c} linked={shipment.status !== 'SHIPPED'} />
              {shipment.status === 'SHIPPED' || c.loadedAt || !may ? (
                <p className="muted ship-carton__tracking">
                  {c.trackingNumber ? (
                    <>
                      Tracking <span className="code">{c.trackingNumber}</span>
                    </>
                  ) : (
                    'No tracking number'
                  )}
                  {c.loadedAt && shipment.status !== 'SHIPPED' && ' · Loaded'}
                </p>
              ) : (
                <TrackingForm
                  key={c.trackingNumber ?? ''}
                  carton={c}
                  busy={busy}
                  onSave={(number) => run(() => setTracking(shipment.id, c.id, number), number ? 'Tracking number saved' : 'Tracking number cleared')}
                />
              )}
            </div>
          ))}
        </div>
      </section>

      {editingAddress && (
        <AddressForm
          order={shipment.order}
          shipTo={shipment.shipTo}
          onClose={() => setEditingAddress(false)}
          onSaved={() => {
            setEditingAddress(false);
            void run(() => getShipment(shipment.id), 'Address saved');
          }}
        />
      )}
    </div>
  );
}

function TrackingForm({ carton, busy, onSave }: { carton: Carton; busy: boolean; onSave: (number: string) => void }) {
  const [value, setValue] = useState(carton.trackingNumber ?? '');
  const changed = value.trim().toUpperCase() !== (carton.trackingNumber ?? '');
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (changed) onSave(value.trim());
  };
  return (
    <form className="ship-carton__tracking" onSubmit={submit}>
      <input
        className="input--code"
        value={value}
        maxLength={60}
        autoComplete="off"
        placeholder="Tracking number"
        aria-label={`Tracking number for ${carton.container.lpnId}`}
        onChange={(e) => setValue(e.target.value)}
      />
      <button type="submit" className="button" disabled={busy || !changed}>
        Save
      </button>
    </form>
  );
}
