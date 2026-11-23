import { useCallback, useEffect, useState } from 'react';
import {
  checkLpn,
  closeAsn,
  discardBlind,
  getOpenAsn,
  getReceivingLocation,
  listOpenAsns,
  listReceivingLocations,
  receiveItems,
  receiveWholeLpn,
  reverseReceipt,
  startBlind,
  type AsnDetail,
  type AsnReceipt,
  type AsnSummary,
  type LpnCheck,
  type ReceiveInput,
  type ReceiveLpnInput,
  type ReceivingLocation,
} from '../../api/asns';
import { ApiError, errorMessage } from '../../api/http';
import { inventoryStatusLabel } from '../../api/inventory';
import { ownersAsked } from '../../api/owners';
import { newRequestId } from '../../forms/requestId';
import { formatDay } from '../outbound/OrdersPage';
import { ReceiveItems, type ItemEntry } from './ReceiveItems';
import { ScanInput } from './ScanInput';

const LAST_TYPE = 'wms.receiving.containerType';

/** Something to receive, kept until the server has answered. */
type Pending =
  | { kind: 'items'; input: ReceiveInput; done: string }
  | { kind: 'lpn'; input: ReceiveLpnInput; done: string };

type Mode = 'receive' | 'undo' | 'close';

/** What nobody announced, before it is started. */
interface Blind {
  /** Kept while this is on the screen, so sending it again starts nothing more. */
  requestId: string;
  vendor: string;
  reference: string;
  /** Owners it could be for, when there are several. */
  owners: string[];
}

/** Receiving an ASN, or what nobody announced: where, which, then LPN by LPN, whole or item by item. */
export function ReceivingTransaction({ onSignedOut }: { onSignedOut: () => void }) {
  const [locations, setLocations] = useState<ReceivingLocation[] | null>(null);
  const [location, setLocation] = useState<ReceivingLocation | null>(null);
  const [asns, setAsns] = useState<AsnSummary[]>([]);
  const [asn, setAsn] = useState<AsnDetail | null>(null);
  const [lpn, setLpn] = useState<LpnCheck | null>(null);
  const [typeId, setTypeId] = useState<string | null>(null);
  /** The vendor's LPN is opened and counted instead of taken as announced. */
  const [byItem, setByItem] = useState(false);
  const [mode, setMode] = useState<Mode>('receive');
  const [blind, setBlind] = useState<Blind | null>(null);
  const [undoing, setUndoing] = useState<AsnReceipt | null>(null);
  const [shortBy, setShortBy] = useState<string | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  /** More than expected, waiting for a yes. */
  const [over, setOver] = useState<{ pending: Pending; message: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const fail = useCallback(
    (e: unknown) => {
      if (e instanceof ApiError && e.status === 401) onSignedOut();
      else setError(errorMessage(e));
    },
    [onSignedOut],
  );

  useEffect(() => {
    const controller = new AbortController();
    listReceivingLocations(controller.signal)
      .then(setLocations)
      .catch((e) => {
        if (!controller.signal.aborted) fail(e);
      });
    return () => controller.abort();
  }, [fail]);

  const severalOwners = new Set(asns.map((a) => a.owner?.code)).size > 1;

  const leaveLpn = () => {
    setLpn(null);
    setTypeId(null);
    setByItem(false);
    setOver(null);
  };

  const leaveAsn = async () => {
    leaveLpn();
    setAsn(null);
    setMode('receive');
    setUndoing(null);
    setShortBy(null);
    try {
      setAsns(await listOpenAsns());
    } catch (e) {
      fail(e);
    }
  };

  /** Runs one action; true if it worked. */
  const run = async (action: () => Promise<void>): Promise<boolean> => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      await action();
      return true;
    } catch (e) {
      fail(e);
      // Someone else closed or changed it: show where it stands now.
      if (e instanceof ApiError && e.status === 409 && asn) await refresh(asn);
      return false;
    } finally {
      setBusy(false);
    }
  };

  const refresh = async (current: AsnDetail) => {
    try {
      setAsn(await getOpenAsn(current.asnNumber, current.owner?.code));
    } catch (e) {
      if (e instanceof ApiError && e.status === 400) await leaveAsn();
    }
  };

  const chooseLocation = (code: string) =>
    run(async () => {
      const found = await getReceivingLocation(code);
      setAsns(await listOpenAsns());
      setLocation(found);
    });

  const chooseAsn = (number: string, owner?: string) => run(async () => setAsn(await getOpenAsn(number, owner)));

  const beginBlind = async (owner: string | null) => {
    const start = blind!;
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      setAsn(await startBlind({ requestId: start.requestId, owner, vendorName: start.vendor.trim(), reference: start.reference.trim() }));
      setBlind(null);
    } catch (e) {
      const owners = e instanceof ApiError ? ownersAsked(e.fieldErrors) : [];
      if (owners.length > 0) setBlind({ ...start, owners });
      else fail(e);
    } finally {
      setBusy(false);
    }
  };

  const scanLpn = (scanned: string) =>
    run(async () => {
      const found = await checkLpn(asn!.id, location!.locationId, scanned);
      setLpn(found);
      setTypeId(null);
      setByItem(found.expected.length === 0 || found.whyNotAll !== null);
    });

  const chooseType = (id: string) => {
    setTypeId(id);
    try {
      localStorage.setItem(LAST_TYPE, id);
    } catch {
      // Only a convenience.
    }
  };

  /** Sends a receipt. If there is no answer it is kept, so it can be sent again without being received twice. */
  const send = async (receipt: Pending) => {
    setBusy(true);
    setError(null);
    setDone(null);
    setOver(null);
    try {
      const next = receipt.kind === 'items' ? await receiveItems(asn!.id, receipt.input) : await receiveWholeLpn(asn!.id, receipt.input);
      setPending(null);
      setAsn(next);
      setDone(receipt.done);
      if (receipt.kind === 'lpn') leaveLpn();
      // The LPN exists now, so its type isn't asked for again.
      else setLpn((current) => current && { ...current, needsType: false, kind: 'OPEN' });
    } catch (e) {
      if (!(e instanceof ApiError) || e.status >= 500) {
        setPending(receipt);
      } else {
        setPending(null);
        if (e.status === 409 && e.fieldErrors.over) {
          setOver({ pending: receipt, message: e.message });
        } else {
          fail(e);
          if (e.status === 409) await refresh(asn!);
        }
      }
    } finally {
      setBusy(false);
    }
  };

  const sendItems = (entry: ItemEntry) =>
    send({
      kind: 'items',
      input: {
        requestId: newRequestId(),
        locationId: location!.locationId,
        lpnId: lpn!.lpnId,
        containerTypeId: typeId,
        itemBarcode: entry.scan.value,
        quantity: entry.quantity,
        lotNumber: entry.lotNumber,
        expiryDate: entry.expiryDate,
        serialNumbers: entry.serialNumbers,
        condition: entry.condition,
        allowOver: false,
      },
      done: `Received ${entry.quantity.toLocaleString()} ${entry.scan.uom} of ${entry.item.itemId} into ${lpn!.lpnId}`,
    });

  const close = async (acceptShort: boolean) => {
    const current = asn!;
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      await closeAsn(current.id, acceptShort);
      await leaveAsn();
      setDone(`${current.asnNumber} is closed.`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.fieldErrors.short) setShortBy(e.message);
      else {
        fail(e);
        if (e instanceof ApiError && e.status === 409) await refresh(current);
      }
    } finally {
      setBusy(false);
    }
  };

  const doneNote = done && (
    <p className="rf-done" role="status">
      {done}
    </p>
  );
  const errorNote = error && (
    <p className="rf-error" role="alert">
      {error}
    </p>
  );

  // ---------- No answer ----------

  if (pending) {
    return (
      <>
        <h1 className="rf-title">Receiving</h1>
        <section className="rf-card" role="alert">
          <p className="rf-card__action">No answer from the server</p>
          <p className="muted">Send it again when you have a signal. If it did get through, it won't be received twice.</p>
        </section>
        <button type="button" className="rf-button rf-button--primary" disabled={busy} onClick={() => void send(pending)}>
          {busy ? 'Sending…' : 'Send again'}
        </button>
      </>
    );
  }

  // ---------- Where ----------

  if (!location) {
    return (
      <>
        <h1 className="rf-title">Receiving</h1>
        <section className="rf-card">
          <p className="rf-card__action">Scan a receiving location or dock door</p>
        </section>
        <ScanInput label="Receiving location or door" busy={busy} error={error} onScan={(code) => void chooseLocation(code)} />
        {locations?.length === 0 && <p className="muted">Nowhere to receive is set up. Add a location of type Receiving or Dock door.</p>}
        <div className="rf-choices">
          {locations?.map((l) => (
            <button key={l.id} type="button" className="rf-button" disabled={busy} onClick={() => void chooseLocation(l.locationId)}>
              <strong className="code">{l.locationId}</strong> <span className="muted">{l.type}</span>
            </button>
          ))}
        </div>
      </>
    );
  }

  // ---------- Without an ASN ----------

  if (!asn && blind) {
    return (
      <>
        <h1 className="rf-title">Receive without an ASN</h1>
        {blind.owners.length > 0 ? (
          <section className="rf-card" role="group" aria-label="Whose it is">
            <p className="rf-card__action">Whose is it?</p>
            <div className="rf-choices">
              {blind.owners.map((o) => (
                <button key={o} type="button" className="rf-button" disabled={busy} onClick={() => void beginBlind(o)}>
                  {o}
                </button>
              ))}
            </div>
            {errorNote}
          </section>
        ) : (
          <form
            className="scan"
            onSubmit={(event) => {
              event.preventDefault();
              void beginBlind(null);
            }}
          >
            <label className="scan__label" htmlFor="rf-blind-vendor">
              Who is it from? (optional)
            </label>
            <input
              id="rf-blind-vendor"
              className="scan__input"
              autoFocus
              autoComplete="off"
              maxLength={120}
              value={blind.vendor}
              onChange={(e) => setBlind({ ...blind, vendor: e.target.value })}
            />
            <label className="scan__label" htmlFor="rf-blind-reference">
              PO, bill of lading or trailer (optional)
            </label>
            <input
              id="rf-blind-reference"
              className="scan__input"
              autoComplete="off"
              maxLength={60}
              value={blind.reference}
              onChange={(e) => setBlind({ ...blind, reference: e.target.value })}
            />
            {errorNote}
            <button type="submit" className="rf-button rf-button--primary" disabled={busy}>
              {busy ? 'Starting…' : 'Start receiving'}
            </button>
          </form>
        )}
        <button
          type="button"
          className="rf-button rf-button--quiet"
          disabled={busy}
          onClick={() => {
            setError(null);
            setBlind(null);
          }}
        >
          Back
        </button>
      </>
    );
  }

  // ---------- Which ASN ----------

  if (!asn) {
    return (
      <>
        <h1 className="rf-title">
          Receiving at <span className="code">{location.locationId}</span>
        </h1>
        {doneNote}
        <section className="rf-card">
          <p className="rf-card__action">Scan an ASN</p>
        </section>
        <ScanInput label="ASN" busy={busy} error={error} onScan={(number) => void chooseAsn(number)} />
        {asns.length === 0 && <p className="rf-waiting muted">No ASNs are waiting to be received.</p>}
        <div className="rf-choices">
          {asns.map((a) => (
            <button key={a.id} type="button" className="rf-button" disabled={busy} onClick={() => void chooseAsn(a.asnNumber, a.owner?.code)}>
              <strong className="code">{a.asnNumber}</strong> {a.vendorName}
              {severalOwners && a.owner && <span className="muted"> · for {a.owner.code}</span>}
              <span className="muted cell__sub">
                {a.blind
                  ? `No ASN · ${a.receivedQuantity.toLocaleString()} EA received`
                  : `${a.receivedQuantity.toLocaleString()} of ${a.expectedQuantity.toLocaleString()} EA received`}
                {a.expectedDate && ` · due ${formatDay(a.expectedDate)}`}
              </span>
            </button>
          ))}
        </div>
        <button
          type="button"
          className="rf-button"
          disabled={busy}
          onClick={() => {
            setError(null);
            setDone(null);
            setBlind({ requestId: newRequestId(), vendor: '', reference: '', owners: [] });
          }}
        >
          Receive without an ASN
        </button>
        <button
          type="button"
          className="rf-button rf-button--quiet"
          onClick={() => {
            setLocation(null);
            setError(null);
            setDone(null);
          }}
        >
          Different location
        </button>
      </>
    );
  }

  const strip = (
    <section className="rf-strip" aria-label="ASN">
      <div className="rf-strip__row">
        <h1 className="code">{asn.asnNumber}</h1>
        <span>{asn.vendorName}</span>
        {lpn && (
          <span className="rf-strip__into">
            into <span className="code">{lpn.lpnId}</span>
          </span>
        )}
      </div>
      {!asn.blind && (
        <progress className="rf-progress" value={Math.min(asn.receivedQuantity, asn.expectedQuantity)} max={asn.expectedQuantity} aria-label="Received" />
      )}
      <span className="muted">
        {asn.blind
          ? `${asn.receivedQuantity.toLocaleString()} EA received, without an ASN`
          : `${asn.receivedQuantity.toLocaleString()} of ${asn.expectedQuantity.toLocaleString()} EA received`}
      </span>
    </section>
  );

  // ---------- Undoing ----------

  if (mode === 'undo') {
    const receipts = asn.receipts.filter((r) => !r.reversedAt);
    const back = () => {
      setUndoing(null);
      setMode('receive');
      setError(null);
    };
    return (
      <>
        {strip}
        {doneNote}
        {errorNote}
        {undoing ? (
          <section className="rf-card" role="group" aria-label="Undo receipt">
            <p className="rf-card__action">
              Undo {undoing.quantity.toLocaleString()} EA of {undoing.itemId}?
            </p>
            <p className="muted">It is taken out of {undoing.lpn.lpnId} and can be received again.</p>
            <div className="rf-choices">
              <button
                type="button"
                className="rf-button rf-button--danger"
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    setAsn(await reverseReceipt(undoing.id));
                    setDone(`Undone: ${undoing.quantity.toLocaleString()} EA of ${undoing.itemId} out of ${undoing.lpn.lpnId}.`);
                    setUndoing(null);
                  }).then((ok) => {
                    if (!ok) setUndoing(null);
                  })
                }
              >
                Undo it
              </button>
              <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => setUndoing(null)}>
                Keep it
              </button>
            </div>
          </section>
        ) : (
          <>
            <h2 className="rf-card__action">Which receipt was wrong?</h2>
            <div className="rf-choices">
              {receipts.map((r) => (
                <button
                  key={r.id}
                  type="button"
                  className="rf-button"
                  disabled={busy}
                  onClick={() => {
                    setError(null);
                    setDone(null);
                    setUndoing(r);
                  }}
                >
                  <strong className="code">{r.itemId}</strong> {r.quantity.toLocaleString()} EA
                  <span className="muted cell__sub">
                    {[r.lpn.lpnId, r.lotNumber, r.serialNumber, r.condition === 'AVAILABLE' ? null : inventoryStatusLabel(r.condition)].filter(Boolean).join(' · ')}
                  </span>
                </button>
              ))}
            </div>
            {receipts.length === 0 && <p className="muted">Nothing left to undo.</p>}
            <button type="button" className="rf-button rf-button--quiet" onClick={back}>
              Back to receiving
            </button>
          </>
        )}
      </>
    );
  }

  // ---------- Closing ----------

  if (mode === 'close') {
    const back = () => {
      setShortBy(null);
      setMode('receive');
      setError(null);
    };
    return (
      <>
        {strip}
        {errorNote}
        <section className="rf-card" role="group" aria-label={asn.blind ? 'Finish receipt' : 'Close ASN'}>
          <p className="rf-card__action">{shortBy ?? `Close ${asn.asnNumber}?`}</p>
          <p className="muted">{shortBy ? 'Close it short? Nothing more can be received on it.' : 'Nothing more can be received on it.'}</p>
          <div className="rf-choices">
            <button
              type="button"
              className={`rf-button ${shortBy ? 'rf-button--danger' : 'rf-button--primary'}`}
              disabled={busy}
              onClick={() => void close(shortBy !== null)}
            >
              {shortBy ? 'Close short' : asn.blind ? 'Finish receipt' : 'Close ASN'}
            </button>
            <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={back}>
              Keep receiving
            </button>
          </div>
        </section>
      </>
    );
  }

  // ---------- Which LPN ----------

  if (!lpn) {
    const toCome = [...new Set(asn.lines.filter((l) => l.lpnId && l.remaining > 0).map((l) => l.lpnId!))];
    return (
      <>
        {strip}
        {doneNote}
        <section className="rf-card">
          <p className="rf-card__action">Scan an LPN to receive into</p>
          <p className="muted">{asn.blind ? 'A label on it, a new label, or an empty container.' : "The vendor's LPN, a new label, or an empty container."}</p>
        </section>
        <ScanInput label="LPN" busy={busy} error={error} onScan={(code) => void scanLpn(code)} />
        <button type="button" className="rf-button" disabled={busy} onClick={() => void scanLpn('')}>
          Make a new LPN number
        </button>
        {toCome.length > 0 && (
          <section className="rf-card" aria-label="Vendor's LPNs to come">
            <p className="rf-card__step">Vendor's LPNs to come</p>
            <ul className="rf-list">
              {toCome.map((id) => (
                <li key={id}>
                  <span className="code">{id}</span>
                  <span className="muted">
                    {asn.lines
                      .filter((l) => l.lpnId === id)
                      .reduce((sum, l) => sum + l.remaining, 0)
                      .toLocaleString()}{' '}
                    EA
                  </span>
                </li>
              ))}
            </ul>
          </section>
        )}
        <ItemsToCome asn={asn} />
        {asn.receivedQuantity > 0 && (
          <button
            type="button"
            className={`rf-button${!asn.blind && asn.receivedQuantity >= asn.expectedQuantity ? ' rf-button--primary' : ''}`}
            disabled={busy}
            onClick={() => {
              setError(null);
              setDone(null);
              setMode('close');
            }}
          >
            {asn.blind ? 'Finish receipt' : 'Close ASN'}
          </button>
        )}
        {asn.blind && asn.receivedQuantity === 0 && (
          <button
            type="button"
            className="rf-button rf-button--quiet"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                await discardBlind(asn.id);
                await leaveAsn();
              })
            }
          >
            Discard it: nothing was received
          </button>
        )}
        {asn.receipts.some((r) => !r.reversedAt) && (
          <button
            type="button"
            className="rf-button rf-button--quiet"
            onClick={() => {
              setError(null);
              setDone(null);
              setMode('undo');
            }}
          >
            Undo a receipt
          </button>
        )}
        <button
          type="button"
          className="rf-button rf-button--quiet"
          onClick={() => {
            setError(null);
            setDone(null);
            void leaveAsn();
          }}
        >
          {asn.blind ? 'Leave it for later' : 'Different ASN'}
        </button>
      </>
    );
  }

  // ---------- What it is ----------

  if (lpn.needsType && !typeId) {
    const last = lastType();
    const types = [...lpn.types].sort((a, b) => Number(b.id === last) - Number(a.id === last));
    return (
      <>
        {strip}
        <section className="rf-card">
          <p className="rf-card__step">New LPN</p>
          <p className="rf-card__big code">{lpn.lpnId}</p>
          <p className="rf-card__action">What is it?</p>
        </section>
        {errorNote}
        <div className="rf-choices">
          {types.map((t) => (
            <button key={t.id} type="button" className={`rf-button${t.id === last ? ' rf-choice--last' : ''}`} disabled={busy} onClick={() => chooseType(t.id)}>
              <strong>{t.containerTypeId}</strong> {t.description}
              {t.id === last && <span className="muted"> · used last</span>}
            </button>
          ))}
        </div>
        <button type="button" className="rf-button rf-button--quiet" onClick={leaveLpn}>
          Different LPN
        </button>
      </>
    );
  }

  // ---------- A whole LPN ----------

  if (!byItem) {
    const total = lpn.expected.reduce((sum, l) => sum + l.remaining, 0);
    return (
      <>
        {strip}
        <section className="rf-card" aria-label="Announced contents">
          <p className="rf-card__step">The vendor says {lpn.lpnId} holds</p>
          <ul className="rf-list">
            {lpn.expected.map((l) => (
              <li key={l.id}>
                <span>
                  <span className="code">{l.item.itemId}</span> {l.item.description}
                  <span className="muted cell__sub">
                    {[l.lotNumber && `lot ${l.lotNumber}`, l.expiryDate && `expires ${formatDay(l.expiryDate)}`].filter(Boolean).join(' · ')}
                  </span>
                </span>
                <strong>{l.remaining.toLocaleString()} EA</strong>
              </li>
            ))}
          </ul>
        </section>
        {errorNote}
        <button
          type="button"
          className="rf-button rf-button--primary"
          disabled={busy}
          onClick={() =>
            void send({
              kind: 'lpn',
              input: {
                requestId: newRequestId(),
                locationId: location.locationId,
                lpnId: lpn.lpnId,
                containerTypeId: typeId,
                condition: 'AVAILABLE',
              },
              done: `Received ${lpn.lpnId}: ${total.toLocaleString()} EA`,
            })
          }
        >
          {busy ? 'Receiving…' : `Receive all ${total.toLocaleString()} EA`}
        </button>
        <button
          type="button"
          className="rf-button"
          disabled={busy}
          onClick={() => {
            setError(null);
            setByItem(true);
          }}
        >
          Count it item by item
        </button>
        <button
          type="button"
          className="rf-button rf-button--quiet"
          onClick={() => {
            setError(null);
            leaveLpn();
          }}
        >
          Different LPN
        </button>
      </>
    );
  }

  // ---------- Item by item ----------

  return (
    <>
      {strip}
      {doneNote}
      {over && (
        <section className="rf-card" role="group" aria-label="More than expected">
          <p className="rf-card__action">{over.message}</p>
          <div className="rf-choices">
            <button
              type="button"
              className="rf-button rf-button--danger"
              disabled={busy}
              onClick={() => {
                const receipt = over.pending;
                if (receipt.kind === 'items') void send({ ...receipt, input: { ...receipt.input, requestId: newRequestId(), allowOver: true } });
              }}
            >
              Receive it anyway
            </button>
            <button type="button" className="rf-button rf-button--quiet" disabled={busy} onClick={() => setOver(null)}>
              Change it
            </button>
          </div>
        </section>
      )}
      <div hidden={over !== null}>
        <ReceiveItems
          key={lpn.lpnId}
          asn={asn}
          lpn={lpn}
          // Busy while the question is open, so the box takes focus again afterwards.
          busy={busy || over !== null}
          error={error}
          onClearError={() => {
            setError(null);
            setDone(null);
          }}
          onReceive={sendItems}
        />
      </div>
      {!over && (
        <button
          type="button"
          className="rf-button"
          disabled={busy}
          onClick={() => {
            setError(null);
            setDone(null);
            leaveLpn();
          }}
        >
          Next LPN
        </button>
      )}
    </>
  );
}

function ItemsToCome({ asn }: { asn: AsnDetail }) {
  const items = new Map<string, { description: string; remaining: number }>();
  for (const l of asn.lines.filter((line) => !line.lpnId && line.remaining > 0)) {
    items.set(l.item.itemId, { description: l.item.description, remaining: (items.get(l.item.itemId)?.remaining ?? 0) + l.remaining });
  }
  if (items.size === 0) return null;
  return (
    <section className="rf-card" aria-label="Items to come">
      <p className="rf-card__step">Items to come</p>
      <ul className="rf-list">
        {[...items].map(([itemId, { description, remaining }]) => (
          <li key={itemId}>
            <span>
              <span className="code">{itemId}</span> <span className="muted">{description}</span>
            </span>
            <span>{remaining.toLocaleString()} EA</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function lastType(): string | null {
  try {
    return localStorage.getItem(LAST_TYPE);
  } catch {
    return null;
  }
}
