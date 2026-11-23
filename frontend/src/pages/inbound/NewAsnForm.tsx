import { useState, type FormEvent } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { createAsn, type AsnDetail } from '../../api/asns';
import { ApiError, errorMessage } from '../../api/http';
import { listItems, type Item } from '../../api/items';
import { useOwners } from '../../api/owners';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';
import { OwnerSelect } from '../../components/OwnerSelect';
import { SearchPicker } from '../../components/SearchPicker';
import { positiveIntegerError } from '../../forms/validation';

interface NewAsnFormProps {
  onClose: () => void;
  onCreated: (asn: AsnDetail) => void;
}

interface LineValues {
  key: number;
  item: Item | null;
  quantity: string;
  lotNumber: string;
  expiryDate: string;
  lpnId: string;
}

type Errors = Partial<Record<string, string>>;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

let nextKey = 0;
const newLine = (): LineValues => ({ key: ++nextKey, item: null, quantity: '', lotNumber: '', expiryDate: '', lpnId: '' });

/** Enter an ASN by hand, as a vendor would send it. */
export function NewAsnForm({ onClose, onCreated }: NewAsnFormProps) {
  const [asnNumber, setAsnNumber] = useState('');
  const [vendor, setVendor] = useState('');
  const [reference, setReference] = useState('');
  const [expectedDate, setExpectedDate] = useState('');
  const [lines, setLines] = useState<LineValues[]>(() => [newLine()]);
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
    clear(...Object.keys(changes).map((k) => `lines[${index}].${k === 'item' ? 'itemId' : k}`));
  };

  const validate = (): Errors => {
    const found: Errors = {};
    if (asnNumber.trim() && !CODE_PATTERN.test(asnNumber.trim())) found.asnNumber = "Use letters, numbers, '-', '_' or '.'";
    if (several && !owner) found.owner = 'Choose whose it is';
    if (!vendor.trim()) found.vendorName = 'Enter the vendor';
    lines.forEach((line, i) => {
      if (!line.item) found[`lines[${i}].itemId`] = 'Choose an item';
      const qty = positiveIntegerError(line.quantity);
      if (qty) found[`lines[${i}].quantity`] = qty;
      if (line.lpnId.trim() && !CODE_PATTERN.test(line.lpnId.trim())) found[`lines[${i}].lpnId`] = "Use letters, numbers, '-', '_' or '.'";
    });
    return found;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    try {
      onCreated(
        await createAsn({
          owner: owner || undefined,
          asnNumber: asnNumber.trim() || null,
          vendorName: vendor.trim(),
          reference: reference.trim() || null,
          expectedDate: expectedDate || null,
          lines: lines.map((l) => ({
            itemId: l.item!.itemId,
            quantity: Number(l.quantity),
            lotNumber: (l.item!.lotControlled && l.lotNumber.trim()) || null,
            expiryDate: (l.item!.expiryControlled && l.expiryDate) || null,
            lpnId: l.lpnId.trim() || null,
          })),
        }),
      );
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
      <button type="submit" form="asn-form" className="button button--primary" disabled={saving}>
        {saving ? 'Creating…' : 'Create ASN'}
      </button>
    </div>
  );

  return (
    <Drawer title="New ASN" onClose={onClose} footer={footer} wide>
      <form id="asn-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <OwnerSelect
          id="asn-owner"
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
          <Field id="asn-vendor" label="Vendor" error={errors.vendorName}>
            <input
              id="asn-vendor"
              value={vendor}
              maxLength={120}
              aria-invalid={Boolean(errors.vendorName)}
              onChange={(e) => {
                setVendor(e.target.value);
                clear('vendorName');
              }}
            />
          </Field>
          <Field id="asn-number" label="ASN number" error={errors.asnNumber} hint="Leave blank to assign one">
            <input
              id="asn-number"
              className="input--code"
              value={asnNumber}
              maxLength={40}
              autoComplete="off"
              aria-invalid={Boolean(errors.asnNumber)}
              onChange={(e) => {
                setAsnNumber(e.target.value.toUpperCase());
                clear('asnNumber');
              }}
            />
          </Field>
          <Field id="asn-reference" label="Reference" error={errors.reference} hint="PO, bill of lading or trailer">
            <input id="asn-reference" value={reference} maxLength={60} onChange={(e) => setReference(e.target.value)} />
          </Field>
          <Field id="asn-date" label="Expected date" error={errors.expectedDate}>
            <input id="asn-date" type="date" value={expectedDate} onChange={(e) => setExpectedDate(e.target.value)} />
          </Field>
        </div>

        <fieldset className="fieldset">
          <legend>Lines</legend>
          <p className="field__hint">Give a line the vendor's LPN if it is already labelled; that LPN can then be received with one scan.</p>
          {errors.lines && <p className="field__error">{errors.lines}</p>}
          <ol className="order-lines">
            {lines.map((line, i) => {
              const err = (f: string) => errors[`lines[${i}].${f}`];
              const id = (f: string) => `asn-line-${line.key}-${f}`;
              return (
                <li key={line.key} className="order-line" aria-label={`Line ${i + 1}`}>
                  <span className="order-line__number">{i + 1}</span>
                  <Field id={id('item')} label="Item" error={err('itemId')}>
                    <SearchPicker<Item>
                      id={id('item')}
                      value={line.item}
                      onChange={(item) => updateLine(i, { item, lotNumber: '', expiryDate: '' })}
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
                  <Field id={id('qty')} label="Quantity (EA)" error={err('quantity')}>
                    <input
                      id={id('qty')}
                      type="number"
                      inputMode="numeric"
                      min="1"
                      step="1"
                      value={line.quantity}
                      aria-invalid={Boolean(err('quantity'))}
                      onChange={(e) => updateLine(i, { quantity: e.target.value })}
                    />
                  </Field>
                  <Field id={id('lpn')} label="Vendor's LPN" error={err('lpnId')} hint="Optional">
                    <input
                      id={id('lpn')}
                      className="input--code"
                      value={line.lpnId}
                      maxLength={40}
                      autoComplete="off"
                      aria-invalid={Boolean(err('lpnId'))}
                      onChange={(e) => updateLine(i, { lpnId: e.target.value.toUpperCase() })}
                    />
                  </Field>
                  <button
                    type="button"
                    className="icon-button order-line__remove"
                    aria-label={`Remove line ${i + 1}`}
                    disabled={lines.length === 1}
                    onClick={() => {
                      setLines((current) => current.filter((_, idx) => idx !== i));
                      setErrors({});
                    }}
                  >
                    <Trash2 size={16} aria-hidden="true" />
                  </button>
                  {(line.item?.lotControlled || line.item?.expiryControlled) && (
                    <div className="asn-line__more">
                      {line.item.lotControlled && (
                        <Field id={id('lot')} label="Lot" error={err('lotNumber')} hint="If known">
                          <input
                            id={id('lot')}
                            className="input--code"
                            value={line.lotNumber}
                            maxLength={40}
                            autoComplete="off"
                            onChange={(e) => updateLine(i, { lotNumber: e.target.value.toUpperCase() })}
                          />
                        </Field>
                      )}
                      {line.item.expiryControlled && (
                        <Field id={id('expiry')} label="Expiry date" error={err('expiryDate')} hint="If known">
                          <input id={id('expiry')} type="date" value={line.expiryDate} onChange={(e) => updateLine(i, { expiryDate: e.target.value })} />
                        </Field>
                      )}
                    </div>
                  )}
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
