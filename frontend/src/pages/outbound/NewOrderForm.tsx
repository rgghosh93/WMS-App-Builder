import { useState, type FormEvent } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { createOrder, type OrderDetail, type OrderInput } from '../../api/orders';
import { listItems, type Item } from '../../api/items';
import { useOwners } from '../../api/owners';
import { ApiError, errorMessage } from '../../api/http';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';
import { OwnerSelect } from '../../components/OwnerSelect';
import { SearchPicker } from '../../components/SearchPicker';
import { positiveIntegerError } from '../../forms/validation';
import type { ShipTo } from '../../api/shipping';
import { AddressFields, EMPTY_ADDRESS, addressErrors, isBlankAddress, toShipTo } from '../shipping/AddressFields';

interface NewOrderFormProps {
  onClose: () => void;
  onCreated: (order: OrderDetail) => void;
}

interface LineValues {
  key: number;
  item: Item | null;
  quantity: string;
  lot: string;
}

type Errors = Partial<Record<string, string>>;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const PRIORITIES = [1, 2, 3, 4, 5, 6, 7, 8, 9];

let nextKey = 0;
const newLine = (): LineValues => ({ key: ++nextKey, item: null, quantity: '', lot: '' });

/** Enter an order by hand. It is allocated as soon as it is saved unless held back to batch with other orders. */
export function NewOrderForm({ onClose, onCreated }: NewOrderFormProps) {
  const [orderNumber, setOrderNumber] = useState('');
  const [customer, setCustomer] = useState('');
  const [shipDate, setShipDate] = useState('');
  const [priority, setPriority] = useState(5);
  const [allocateNow, setAllocateNow] = useState(true);
  const [lines, setLines] = useState<LineValues[]>(() => [newLine()]);
  const [address, setAddress] = useState(EMPTY_ADDRESS);
  const [owner, setOwner] = useState('');
  const { several } = useOwners();
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const clear = (...keys: string[]) =>
    setErrors((current) => {
      const next = { ...current };
      keys.forEach((k) => delete next[k]);
      return next;
    });

  const updateLine = (index: number, changes: Partial<LineValues>) => {
    setLines((current) => current.map((l, i) => (i === index ? { ...l, ...changes } : l)));
    clear(...Object.keys(changes).map((k) => `lines[${index}].${k === 'item' ? 'itemId' : k === 'lot' ? 'requestedLot' : k}`));
  };

  const validate = (): Errors => {
    const found: Errors = {};
    if (orderNumber.trim() && !CODE_PATTERN.test(orderNumber.trim())) found.orderNumber = "Use letters, numbers, '-', '_' or '.'";
    if (several && !owner) found.owner = 'Choose whose it is';
    if (!customer.trim()) found.customerName = 'Enter the customer';
    // The address is optional here, but once started it has to be complete.
    if (!isBlankAddress(address)) {
      Object.entries(addressErrors(address)).forEach(([field, message]) => (found[`shipTo.${field}`] = message));
    }
    if (lines.length === 0) found.lines = 'Add at least one line';
    lines.forEach((line, i) => {
      if (!line.item) found[`lines[${i}].itemId`] = 'Choose an item';
      const qty = positiveIntegerError(line.quantity);
      if (qty) found[`lines[${i}].quantity`] = qty;
    });
    return found;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    const input: OrderInput = {
      owner: owner || undefined,
      orderNumber: orderNumber.trim() || null,
      customerName: customer.trim(),
      requestedShipDate: shipDate || null,
      priority,
      shipTo: isBlankAddress(address) ? undefined : toShipTo(address),
      lines: lines.map((l) => ({
        itemId: l.item!.itemId,
        quantity: Number(l.quantity),
        requestedLot: l.item!.lotControlled && l.lot.trim() ? l.lot.trim() : null,
      })),
    };
    setSaving(true);
    try {
      onCreated(await createOrder(input, allocateNow));
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
        setFormError(error.message);
      } else {
        setFormError(errorMessage(error));
      }
    } finally {
      setSaving(false);
    }
  };

  const searchItems = (text: string, signal: AbortSignal) =>
    listItems({ q: text, status: 'ACTIVE', owner, page: 0, size: 8, sort: 'itemId', dir: 'asc' }, signal).then((p) => p.content);

  const footer = (
    <div className="button-row button-row--end">
      <button type="button" className="button" onClick={onClose} disabled={saving}>
        Cancel
      </button>
      <button type="submit" form="order-form" className="button button--primary" disabled={saving}>
        {saving ? (allocateNow ? 'Creating and allocating…' : 'Creating…') : 'Create order'}
      </button>
    </div>
  );

  return (
    <Drawer title="New order" onClose={onClose} footer={footer} wide>
      <form id="order-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <label className="check">
          <input type="checkbox" checked={allocateNow} onChange={(e) => setAllocateNow(e.target.checked)} />
          <span>
            <span className="check__label">Allocate now</span>
            <span className="check__hint">
              {allocateNow
                ? 'Stock is reserved as soon as the order is created, on a pick task of its own.'
                : 'The order stays New, so you can allocate it later together with other orders on one batch pick task.'}
            </span>
          </span>
        </label>

        <OwnerSelect
          id="order-owner"
          value={owner}
          error={errors.owner}
          onChange={(code) => {
            setOwner(code);
            // Items are their owner's, so those chosen don't fit another.
            setLines((current) => current.map((l) => ({ ...l, item: null })));
            clear('owner');
          }}
        />
        <div className="grid grid--2">
          <Field id="order-customer" label="Customer" error={errors.customerName}>
            <input
              id="order-customer"
              value={customer}
              maxLength={120}
              aria-invalid={Boolean(errors.customerName)}
              onChange={(e) => {
                setCustomer(e.target.value);
                clear('customerName');
              }}
            />
          </Field>
          <Field id="order-number" label="Order number" error={errors.orderNumber} hint="Leave blank to assign one">
            <input
              id="order-number"
              className="input--code"
              value={orderNumber}
              maxLength={40}
              autoComplete="off"
              aria-invalid={Boolean(errors.orderNumber)}
              onChange={(e) => {
                setOrderNumber(e.target.value.toUpperCase());
                clear('orderNumber');
              }}
            />
          </Field>
          <Field id="order-ship-date" label="Requested ship date">
            <input id="order-ship-date" type="date" value={shipDate} onChange={(e) => setShipDate(e.target.value)} />
          </Field>
          <Field id="order-priority" label="Priority" hint="1 is the most urgent">
            <select id="order-priority" value={priority} onChange={(e) => setPriority(Number(e.target.value))}>
              {PRIORITIES.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <fieldset className="fieldset">
          <legend>Ship to</legend>
          <p className="field__hint">Optional now, but needed before the order can ship.</p>
          <AddressFields
            values={address}
            errors={
              Object.fromEntries(
                Object.entries(errors)
                  .filter(([key]) => key.startsWith('shipTo.'))
                  .map(([key, message]) => [key.slice('shipTo.'.length), message]),
              ) as Partial<Record<keyof ShipTo, string>>
            }
            onChange={(field, value) => {
              setAddress((a) => ({ ...a, [field]: value }));
              clear(`shipTo.${field}`);
            }}
          />
        </fieldset>

        <fieldset className="fieldset">
          <legend>Lines</legend>
          {errors.lines && <p className="field__error">{errors.lines}</p>}
          <ol className="order-lines">
            {lines.map((line, i) => {
              const err = (f: string) => errors[`lines[${i}].${f}`];
              return (
                <li key={line.key} className="order-line" aria-label={`Line ${i + 1}`}>
                  <span className="order-line__number">{i + 1}</span>
                  <Field id={`line-${line.key}-item`} label="Item" error={err('itemId')}>
                    <SearchPicker<Item>
                      id={`line-${line.key}-item`}
                      value={line.item}
                      onChange={(item) => updateLine(i, { item, lot: '' })}
                      search={searchItems}
                      optionKey={(item) => item.id}
                      optionText={(item) => item.itemId}
                      renderOption={(item) => (
                        <>
                          <span className="code">{item.itemId}</span> <span className="muted">{item.description}</span>
                        </>
                      )}
                      invalid={Boolean(err('itemId'))}
                      placeholder="Search items"
                      clearLabel={`Clear item on line ${i + 1}`}
                      emptyText="No matching active items"
                      uppercase
                    />
                    {line.item && <span className="field__hint">{line.item.description}</span>}
                  </Field>
                  <Field id={`line-${line.key}-qty`} label="Quantity (EA)" error={err('quantity')}>
                    <input
                      id={`line-${line.key}-qty`}
                      type="number"
                      inputMode="numeric"
                      min="1"
                      step="1"
                      value={line.quantity}
                      aria-invalid={Boolean(err('quantity'))}
                      onChange={(e) => updateLine(i, { quantity: e.target.value })}
                    />
                  </Field>
                  <Field
                    id={`line-${line.key}-lot`}
                    label="Lot"
                    error={err('requestedLot')}
                    hint={line.item && !line.item.lotControlled ? 'Not lot-tracked' : 'Optional'}
                  >
                    <input
                      id={`line-${line.key}-lot`}
                      className="input--code"
                      value={line.lot}
                      maxLength={40}
                      disabled={!line.item?.lotControlled}
                      onChange={(e) => updateLine(i, { lot: e.target.value.toUpperCase() })}
                    />
                  </Field>
                  <button
                    type="button"
                    className="icon-button order-line__remove"
                    aria-label={`Remove line ${i + 1}`}
                    disabled={lines.length === 1}
                    onClick={() => setLines((current) => current.filter((_, idx) => idx !== i))}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                </li>
              );
            })}
          </ol>
          <button type="button" className="button" onClick={() => setLines((current) => [...current, newLine()])}>
            <Plus size={16} aria-hidden="true" />
            Add line
          </button>
        </fieldset>
      </form>
    </Drawer>
  );
}
