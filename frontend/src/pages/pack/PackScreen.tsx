import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import { WEIGHT_UOMS, type WeightUom } from '../../api/measure';
import {
  closeCarton,
  emptyCarton,
  listCartonTypes,
  openCarton,
  packAsIs,
  packItem,
  reloadPackTask,
  reportMissing,
  startPacking,
  type Carton,
  type CartonWeight,
  type PackDetail,
  type PackTask,
} from '../../api/packing';
import type { ContainerTypeOption, DeviceUser } from '../../api/rf';
import { CartonCard } from '../packing/CartonCard';
import { PackScan } from './PackScan';

interface PackScreenProps {
  user: DeviceUser;
  task: PackTask;
  onBack: () => void;
  onCompleted: (task: PackTask) => void;
  onSignedOut: () => void;
}

/** Packing one container: what to do next on the left, what is in the container and the cartons on the right. */
export function PackScreen({ user, task: initial, onBack, onCompleted, onSignedOut }: PackScreenProps) {
  const [task, setTask] = useState(initial);
  const [orderId, setOrderId] = useState(() => nextOrder(initial));
  const [types, setTypes] = useState<ContainerTypeOption[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [missing, setMissing] = useState<PackDetail | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listCartonTypes(controller.signal)
      .then(setTypes)
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorMessage(e));
      });
    return () => controller.abort();
  }, []);

  const apply = (next: PackTask) => {
    if (next.status === 'COMPLETED') {
      onCompleted(next);
      return;
    }
    setTask(next);
    // Move on to the next order once this one has nothing left and no open carton.
    if (!needsWork(next, orderId)) setOrderId(nextOrder(next));
  };

  /** Runs one action. A conflict means things moved on, so the task is reloaded. */
  const run = async (action: () => Promise<PackTask>, message: string | null = null) => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      apply(await action());
      setDone(message);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onSignedOut();
        return;
      }
      if (e instanceof ApiError && e.status === 409) {
        try {
          apply(await reloadPackTask(task.id));
        } catch {
          // Keep showing what we had; the message below still says what went wrong.
        }
      }
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const order = task.orders.find((o) => o.id === orderId) ?? task.orders[0];
  const carton = task.cartons.find((c) => c.status === 'OPEN' && c.order.id === order.id);
  const left = remainingFor(task, order.id);

  if (task.assignedToId !== user.id) {
    return (
      <div className="station__narrow">
        <section className="rf-card">
          <p className="rf-card__action">
            {task.taskNumber} is now being packed by {task.assignedTo}.
          </p>
          <div className="rf-choices">
            <button
              type="button"
              className="rf-button"
              disabled={busy}
              onClick={() => run(() => startPacking(task.station.locationId, task.container.lpnId, true))}
            >
              Take it back
            </button>
            <button type="button" className="rf-button rf-button--primary" onClick={onBack}>
              Back to the queue
            </button>
          </div>
        </section>
      </div>
    );
  }

  return (
    <div className="station__columns">
      <div className="station__work">
        <section className="rf-strip" aria-label="Packing task">
          <div className="rf-strip__row">
            <h1 className="code">{task.taskNumber}</h1>
            <span className="rf-strip__into">
              packing <span className="code">{task.container.lpnId}</span>
            </span>
          </div>
          <progress className="rf-progress" value={task.packedQuantity + task.shortQuantity} max={task.totalQuantity} aria-label="Packed" />
          <span className="muted">
            {task.packedQuantity.toLocaleString()} of {task.totalQuantity.toLocaleString()} EA packed
            {task.shortQuantity > 0 && ` · ${task.shortQuantity} missing`}
          </span>
        </section>

        {task.orders.length > 1 && (
          <div className="station__orders" role="group" aria-label="Order">
            {task.orders.map((o) => (
              <button
                key={o.id}
                type="button"
                className="station__order"
                aria-pressed={o.id === order.id}
                disabled={busy}
                onClick={() => {
                  setError(null);
                  setDone(null);
                  setOrderId(o.id);
                }}
              >
                <span className="code">{o.orderNumber}</span>
                <span className="muted">{remainingFor(task, o.id) === 0 ? 'Packed' : `${remainingFor(task, o.id)} EA left`}</span>
              </button>
            ))}
          </div>
        )}

        {done && (
          <p className="rf-done" role="status">
            {done}
          </p>
        )}
        {error && !(carton && left > 0) && (
          <p className="rf-error" role="alert">
            {error}
          </p>
        )}

        {missing ? (
          <section className="rf-card" role="group" aria-label="Report missing">
            <p className="rf-card__action">
              {missing.remaining} EA of {missing.item.itemId} missing?
            </p>
            <p className="muted">
              It is put on hold at {task.station.locationId} to be looked into, and {missing.order.orderNumber} needs it again.
            </p>
            <div className="rf-choices">
              <button
                type="button"
                className="rf-button rf-button--danger"
                disabled={busy}
                onClick={() => {
                  const detail = missing;
                  setMissing(null);
                  void run(() => reportMissing(task.id, detail.id, detail.remaining), `${detail.item.itemId} reported missing.`);
                }}
              >
                Report missing
              </button>
              <button type="button" className="rf-button rf-button--quiet" onClick={() => setMissing(null)}>
                Go back
              </button>
            </div>
          </section>
        ) : carton ? (
          <>
            {left > 0 && (
              <PackScan
                key={`${carton.id}-${task.packedQuantity}`}
                task={task}
                order={order}
                carton={carton}
                busy={busy}
                error={error}
                onClearError={() => setError(null)}
                onPack={(scan, summary) => run(() => packItem(task.id, scan), summary)}
              />
            )}
            <CloseCarton
              carton={carton}
              busy={busy}
              prominent={left === 0}
              onClose={(weight) => run(() => closeCarton(task.id, carton.id, weight), `${carton.container.lpnId} closed.`)}
              onEmpty={() => run(() => emptyCarton(task.id, carton.id), `${carton.container.lpnId} emptied and discarded.`)}
            />
          </>
        ) : left > 0 ? (
          <>
            {task.canPackAsIs && (
              <WeightForm
                title={`Ship in ${task.container.lpnId}`}
                hint="This carton was picked for one order, so it can ship as it is."
                action="Ship as is"
                busy={busy}
                onSubmit={(weight) => run(() => packAsIs(task.id, weight))}
              />
            )}
            <OpenCarton
              orderNumber={order.orderNumber}
              customerName={order.customerName}
              types={types}
              busy={busy}
              onOpen={(typeId, lpn) => run(() => openCarton(task.id, order.id, typeId, lpn))}
            />
          </>
        ) : (
          <p className="muted">Everything for {order.orderNumber} is packed. Choose another order.</p>
        )}

        <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={onBack}>
          Back to the queue
        </button>
      </div>

      <div className="station__contents">
        <section aria-labelledby="pack-contents-title">
          <h2 id="pack-contents-title">In {task.container.lpnId}</h2>
          <div className="table-wrap">
            <table className="table" aria-label="Container contents">
              <thead>
                <tr>
                  {task.orders.length > 1 && <th scope="col">Order</th>}
                  <th scope="col">Item</th>
                  <th scope="col">Lot / serial</th>
                  <th scope="col" className="num">
                    Packed
                  </th>
                  <th scope="col">
                    <span className="visually-hidden">Report</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {task.details.map((d) => (
                  <tr key={d.id} className={d.remaining === 0 ? 'station__row--done' : undefined}>
                    {task.orders.length > 1 && <td className="code">{d.order.orderNumber}</td>}
                    <td>
                      <span className="code">{d.item.itemId}</span>
                      <span className="muted cell__sub">{d.item.description}</span>
                    </td>
                    <td className="code">{d.serialNumber ?? d.lotNumber ?? <span className="muted">—</span>}</td>
                    <td className="num">
                      {d.packedQuantity} / {d.quantity} EA
                      {d.shortQuantity > 0 && <span className="muted cell__sub">{d.shortQuantity} missing</span>}
                    </td>
                    <td>
                      {d.remaining > 0 && (
                        <button
                          type="button"
                          className="link-button link-button--small"
                          disabled={busy}
                          aria-label={`Report ${d.item.itemId} missing`}
                          onClick={() => {
                            setError(null);
                            setMissing(d);
                          }}
                        >
                          Missing
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
        {task.cartons.length > 0 && (
          <section aria-labelledby="pack-cartons-title">
            <h2 id="pack-cartons-title">Cartons</h2>
            <div className="carton-list">
              {task.cartons.map((c) => (
                <CartonCard key={c.id} carton={c} showOrder={task.orders.length > 1} />
              ))}
            </div>
          </section>
        )}
      </div>
    </div>
  );
}

function remainingFor(task: PackTask, orderId: string): number {
  return task.details.filter((d) => d.order.id === orderId).reduce((sum, d) => sum + d.remaining, 0);
}

function needsWork(task: PackTask, orderId: string): boolean {
  return remainingFor(task, orderId) > 0 || task.cartons.some((c) => c.status === 'OPEN' && c.order.id === orderId);
}

/** The first order with something left to do. */
function nextOrder(task: PackTask): string {
  return (task.orders.find((o) => needsWork(task, o.id)) ?? task.orders[0]).id;
}

// ---------- Cartons ----------

interface OpenCartonProps {
  orderNumber: string;
  customerName: string;
  types: ContainerTypeOption[];
  busy: boolean;
  onOpen: (containerTypeId: string, lpnId: string | null) => void;
}

function OpenCarton({ orderNumber, customerName, types, busy, onOpen }: OpenCartonProps) {
  const [label, setLabel] = useState('');
  return (
    <section className="rf-card" role="group" aria-label="Open a carton">
      <p className="rf-card__action">Open a carton for {orderNumber}</p>
      <p className="muted">{customerName}</p>
      <label className="scan__label" htmlFor="pack-label">
        Carton label
      </label>
      <input
        id="pack-label"
        className="scan__input"
        value={label}
        autoFocus
        autoComplete="off"
        spellCheck={false}
        placeholder="Scan it, or leave blank for a new number"
        onChange={(e) => setLabel(e.target.value)}
      />
      <span className="scan__label">Choose the carton to open it</span>
      <div className="rf-choices">
        {types.map((t) => (
          <button key={t.id} type="button" className="rf-button" disabled={busy} onClick={() => onOpen(t.id, label.trim() || null)}>
            <strong>{t.containerTypeId}</strong> {t.description}
          </button>
        ))}
      </div>
      {types.length === 0 && <p className="muted">No carton types are set up. Add a single-use container type of category Carton.</p>}
    </section>
  );
}

interface CloseCartonProps {
  carton: Carton;
  busy: boolean;
  /** Nothing is left to pack for the order, so closing is what comes next. */
  prominent: boolean;
  onClose: (weight: CartonWeight) => void;
  onEmpty: () => void;
}

function CloseCarton({ carton, busy, prominent, onClose, onEmpty }: CloseCartonProps) {
  const [emptying, setEmptying] = useState(false);
  if (emptying) {
    return (
      <section className="rf-card" role="group" aria-label="Empty carton">
        <p className="rf-card__action">Empty {carton.container.lpnId}?</p>
        <p className="muted">Everything in it goes back, and the carton is discarded.</p>
        <div className="rf-choices">
          <button type="button" className="rf-button rf-button--danger" disabled={busy} onClick={onEmpty}>
            Empty carton
          </button>
          <button type="button" className="rf-button rf-button--quiet" onClick={() => setEmptying(false)}>
            Keep it
          </button>
        </div>
      </section>
    );
  }
  return (
    <>
      {carton.contents.length > 0 && (
        <WeightForm
          title={`Close ${carton.container.lpnId}`}
          hint={`${carton.totalQuantity.toLocaleString()} EA in it`}
          action="Close carton"
          primary={prominent}
          focus={prominent}
          busy={busy}
          onSubmit={onClose}
        />
      )}
      <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => (carton.contents.length ? setEmptying(true) : onEmpty())}>
        {carton.contents.length ? 'Empty this carton' : 'Discard this carton'}
      </button>
    </>
  );
}

interface WeightFormProps {
  title: string;
  hint: string;
  action: string;
  busy: boolean;
  primary?: boolean;
  /** Put the cursor here: this is what comes next. */
  focus?: boolean;
  onSubmit: (weight: CartonWeight) => void;
}

function WeightForm({ title, hint, action, busy, primary = true, focus = false, onSubmit }: WeightFormProps) {
  const [weight, setWeight] = useState('');
  const [uom, setUom] = useState<WeightUom>('LB');
  const [error, setError] = useState<string | null>(null);
  const weightRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (focus) weightRef.current?.focus();
  }, [focus]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const text = weight.trim();
    if (text && (!/^[0-9]+([.][0-9]{1,3})?$/.test(text) || Number(text) <= 0)) {
      setError('Enter a weight above 0, with up to 3 decimals');
      return;
    }
    setError(null);
    onSubmit({ weight: text ? Number(text) : null, weightUom: text ? uom : null });
  };

  return (
    <form className="rf-card" onSubmit={submit} noValidate aria-label={title}>
      <p className="rf-card__action">{title}</p>
      <p className="muted">{hint}</p>
      <div className="scan__row">
        <input
          ref={weightRef}
          className="scan__input"
          value={weight}
          inputMode="decimal"
          autoComplete="off"
          aria-label="Weight"
          placeholder="Weight (optional)"
          aria-invalid={Boolean(error)}
          onChange={(e) => setWeight(e.target.value)}
        />
        <select className="scan__input station__uom" aria-label="Weight unit" value={uom} onChange={(e) => setUom(e.target.value as WeightUom)}>
          {WEIGHT_UOMS.map((u) => (
            <option key={u.value} value={u.value}>
              {u.label}
            </option>
          ))}
        </select>
      </div>
      {error && (
        <p className="rf-error" role="alert">
          {error}
        </p>
      )}
      <button type="submit" className={`rf-button${primary ? ' rf-button--primary' : ''}`} disabled={busy}>
        {action}
      </button>
    </form>
  );
}
