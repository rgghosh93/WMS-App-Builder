import { useState, type FormEvent } from 'react';
import { addStock, type Adjustment } from '../../../api/control';
import { INVENTORY_STATUSES, type InventoryStatus } from '../../../api/inventory';
import { listItems, type Item } from '../../../api/items';
import { useOwners } from '../../../api/owners';
import { Drawer } from '../../../components/Drawer';
import { Field } from '../../../components/Field';
import { SearchPicker } from '../../../components/SearchPicker';
import { positiveIntegerError } from '../../../forms/validation';
import { ReasonFields } from './ReasonFields';
import { useSubmit } from './useSubmit';

interface AddStockFormProps {
  limit: number | null;
  onClose: () => void;
  onDone: (adjustment: Adjustment) => void;
}

/** Stock found where none was recorded. */
export function AddStockForm({ limit, onClose, onDone }: AddStockFormProps) {
  const [item, setItem] = useState<Item | null>(null);
  const [place, setPlace] = useState('');
  const [inLpn, setInLpn] = useState(false);
  const [quantity, setQuantity] = useState('');
  const [lot, setLot] = useState('');
  const [expiry, setExpiry] = useState('');
  const [serial, setSerial] = useState('');
  const [status, setStatus] = useState<InventoryStatus>('AVAILABLE');
  const [reasonId, setReasonId] = useState('');
  const [note, setNote] = useState('');
  const { several } = useOwners();
  const { errors, formError, saving, submit } = useSubmit();

  const send = (event: FormEvent) => {
    event.preventDefault();
    const found: Record<string, string> = {};
    if (!item) found.itemId = 'Choose an item';
    if (!place.trim()) found[inLpn ? 'lpnId' : 'locationId'] = inLpn ? 'Enter the LPN' : 'Enter the location';
    const amount = item?.serialControlled ? '1' : quantity;
    const wrong = positiveIntegerError(amount);
    if (wrong) found.quantity = wrong;
    if (item?.lotControlled && !lot.trim()) found.lotNumber = 'Enter the lot';
    if (item?.expiryControlled && !expiry) found.expiryDate = 'Enter the expiry date';
    if (item?.serialControlled && !serial.trim()) found.serialNumber = 'Enter the serial number';
    if (!reasonId) found.reasonId = 'Choose a reason';
    void submit(found, async () =>
      onDone(
        await addStock({
          itemId: item!.itemId,
          owner: item!.owner?.code,
          locationId: inLpn ? null : place.trim(),
          lpnId: inLpn ? place.trim() : null,
          quantity: Number(amount),
          lotNumber: lot.trim() || null,
          expiryDate: expiry || null,
          serialNumber: serial.trim() || null,
          status,
          reasonId,
          note: note.trim(),
        }),
      ),
    );
  };

  return (
    <Drawer
      title="Add found stock"
      onClose={onClose}
      footer={
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" form="add-stock-form" className="button button--primary" disabled={saving}>
            {saving ? 'Saving…' : 'Add it'}
          </button>
        </div>
      }
    >
      <form id="add-stock-form" className="form" onSubmit={send} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <p className="muted">For stock with no record of it there. {limit !== null && `More than ${limit.toLocaleString()} EA needs a second person.`}</p>
        <Field id="add-item" label="Item" error={errors.itemId}>
          <SearchPicker<Item>
            id="add-item"
            value={item}
            onChange={setItem}
            search={(text, signal) => listItems({ q: text, status: 'ACTIVE', page: 0, size: 8, sort: 'itemId', dir: 'asc' }, signal).then((p) => p.content)}
            optionKey={(i) => i.id}
            optionText={(i) => i.itemId}
            renderOption={(i) => (
              <>
                <span className="code">{i.itemId}</span> <span className="muted">{i.description}</span>
                {several && i.owner && <span className="muted"> · {i.owner.code}</span>}
              </>
            )}
            invalid={Boolean(errors.itemId)}
            placeholder="Search items"
            clearLabel="Clear item"
            emptyText="No matching active items"
            uppercase
          />
        </Field>
        <div className="grid grid--2">
          <Field id="add-where" label="Found">
            <select id="add-where" value={inLpn ? 'lpn' : 'location'} onChange={(e) => setInLpn(e.target.value === 'lpn')}>
              <option value="location">Loose at a location</option>
              <option value="lpn">In an LPN</option>
            </select>
          </Field>
          <Field id="add-place" label={inLpn ? 'LPN' : 'Location'} error={errors.lpnId ?? errors.locationId}>
            <input
              id="add-place"
              className="input--code"
              value={place}
              maxLength={40}
              autoComplete="off"
              aria-invalid={Boolean(errors.lpnId ?? errors.locationId)}
              onChange={(e) => setPlace(e.target.value.toUpperCase())}
            />
          </Field>
          {item?.lotControlled && (
            <Field id="add-lot" label="Lot" error={errors.lotNumber}>
              <input id="add-lot" className="input--code" value={lot} maxLength={40} autoComplete="off" onChange={(e) => setLot(e.target.value.toUpperCase())} />
            </Field>
          )}
          {item?.expiryControlled && (
            <Field id="add-expiry" label="Expiry date" error={errors.expiryDate}>
              <input id="add-expiry" type="date" value={expiry} onChange={(e) => setExpiry(e.target.value)} />
            </Field>
          )}
          {item?.serialControlled ? (
            <Field id="add-serial" label="Serial number" error={errors.serialNumber}>
              <input id="add-serial" className="input--code" value={serial} maxLength={60} autoComplete="off" onChange={(e) => setSerial(e.target.value.toUpperCase())} />
            </Field>
          ) : (
            <Field id="add-quantity" label="Quantity (EA)" error={errors.quantity}>
              <input
                id="add-quantity"
                type="number"
                inputMode="numeric"
                min="1"
                step="1"
                value={quantity}
                aria-invalid={Boolean(errors.quantity)}
                onChange={(e) => setQuantity(e.target.value)}
              />
            </Field>
          )}
          <Field id="add-status" label="Condition">
            <select id="add-status" value={status} onChange={(e) => setStatus(e.target.value as InventoryStatus)}>
              {INVENTORY_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <ReasonFields id="add" kind="ADJUSTMENT" reasonId={reasonId} note={note} error={errors.reasonId} onReason={setReasonId} onNote={setNote} />
      </form>
    </Drawer>
  );
}
