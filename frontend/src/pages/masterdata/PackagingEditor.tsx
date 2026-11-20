import { Plus, Trash2 } from 'lucide-react';
import type { ItemUom, ItemUomInput } from '../../api/items';
import { DIMENSION_UOMS, WEIGHT_UOMS, type DimensionUom, type WeightUom } from '../../api/measure';
import type { Uom } from '../../api/uoms';
import { Field } from '../../components/Field';
import { optionalNumber, positiveIntegerError, positiveNumberError } from '../../forms/validation';

/** One packaging level as edited in the form (all text, so half-typed values are kept). */
export interface PackValues {
  key: string;
  uomId: string;
  quantity: string;
  barcode: string;
  width: string;
  depth: string;
  height: string;
  dimensionUom: DimensionUom;
  weight: string;
  weightUom: WeightUom;
}

type Errors = Partial<Record<string, string>>;

const MAX_PACKS = 10;
const BARCODE_PATTERN = /^[\x21-\x7E]+$/;
const DIMENSIONS = [
  { field: 'width', label: 'Width' },
  { field: 'depth', label: 'Depth' },
  { field: 'height', label: 'Height' },
] as const;

let nextKey = 0;
const newKey = () => `pack-${++nextKey}`;

export function packsFromItem(uoms: ItemUom[] | undefined): PackValues[] {
  const text = (n: number | null) => (n == null ? '' : String(n));
  return (uoms ?? []).map((p) => ({
    key: newKey(),
    uomId: p.uom.id,
    quantity: String(p.quantity),
    barcode: p.barcode ?? '',
    width: text(p.width),
    depth: text(p.depth),
    height: text(p.height),
    dimensionUom: p.dimensionUom ?? 'IN',
    weight: text(p.weight),
    weightUom: p.weightUom ?? 'LB',
  }));
}

/** Error messages keyed like the server's ("uoms[0].quantity"), so both show in the same place. */
export function validatePacks(packs: PackValues[], itemBarcode: string): Errors {
  const found: Errors = {};
  const seenUnits = new Set<string>();
  const seenBarcodes = new Set<string>();
  packs.forEach((p, i) => {
    const key = (field: string) => `uoms[${i}].${field}`;
    if (!p.uomId) found[key('uomId')] = 'Choose a unit';
    else if (seenUnits.has(p.uomId)) found[key('uomId')] = 'Already listed';
    seenUnits.add(p.uomId);

    const quantityError = positiveIntegerError(p.quantity);
    if (quantityError) found[key('quantity')] = quantityError;
    else if (Number(p.quantity) < 2) found[key('quantity')] = 'At least 2';

    const barcode = p.barcode.trim();
    if (barcode) {
      if (!BARCODE_PATTERN.test(barcode)) found[key('barcode')] = 'No spaces or special characters';
      else if (barcode === itemBarcode.trim()) found[key('barcode')] = "Same as the item's barcode";
      else if (seenBarcodes.has(barcode)) found[key('barcode')] = 'Same as another level';
      seenBarcodes.add(barcode);
    }

    if (DIMENSIONS.some(({ field }) => p[field].trim())) {
      for (const { field } of DIMENSIONS) {
        const message = positiveNumberError(p[field], 2);
        if (message) found[key(field)] = p[field].trim() === '' ? 'Enter all three' : message;
      }
    }
    if (p.weight.trim()) {
      const message = positiveNumberError(p.weight, 3);
      if (message) found[key('weight')] = message;
    }
  });
  return found;
}

export function packsToInput(packs: PackValues[]): ItemUomInput[] {
  return packs.map((p) => {
    const hasDimensions = p.width.trim() !== '';
    const hasWeight = p.weight.trim() !== '';
    return {
      uomId: p.uomId,
      quantity: Number(p.quantity),
      barcode: p.barcode.trim() || null,
      width: hasDimensions ? optionalNumber(p.width) : null,
      depth: hasDimensions ? optionalNumber(p.depth) : null,
      height: hasDimensions ? optionalNumber(p.height) : null,
      dimensionUom: hasDimensions ? p.dimensionUom : null,
      weight: hasWeight ? optionalNumber(p.weight) : null,
      weightUom: hasWeight ? p.weightUom : null,
    };
  });
}

interface PackagingEditorProps {
  packs: PackValues[];
  onChange: (packs: PackValues[]) => void;
  /** All units of measure; the base unit is left out and inactive ones only show where already used. */
  units: Uom[];
  /** The units this item had when the form opened; those stay selectable even if inactive. */
  originalUnitIds: string[];
  baseCode: string;
  errors: Errors;
  onClearError: (key: string) => void;
}

export function PackagingEditor({ packs, onChange, units, originalUnitIds, baseCode, errors, onClearError }: PackagingEditorProps) {
  const choosable = units.filter((u) => !u.base && (u.status === 'ACTIVE' || originalUnitIds.includes(u.id)));

  const update = (index: number, field: keyof PackValues, value: string) => {
    onChange(packs.map((p, i) => (i === index ? { ...p, [field]: value } : p)));
    onClearError(`uoms[${index}].${field}`);
  };

  const add = () => {
    const used = new Set(packs.map((p) => p.uomId));
    const next = choosable.find((u) => u.status === 'ACTIVE' && !used.has(u.id));
    onChange([
      ...packs,
      {
        key: newKey(),
        uomId: next?.id ?? '',
        quantity: '',
        barcode: '',
        width: '',
        depth: '',
        height: '',
        dimensionUom: 'IN',
        weight: '',
        weightUom: 'LB',
      },
    ]);
  };

  const remove = (index: number) => onChange(packs.filter((_, i) => i !== index));

  return (
    <fieldset className="fieldset">
      <legend>Packaging</legend>
      <p className="field__hint">
        Larger units this item is handled in, such as cases and pallets, and how many {baseCode || 'eaches'} each holds.
      </p>

      {packs.length === 0 && <p className="packs__empty">Handled in {baseCode || 'eaches'} only.</p>}

      <ol className="packs">
        {packs.map((p, i) => {
          const err = (field: string) => errors[`uoms[${i}].${field}`];
          const unit = units.find((u) => u.id === p.uomId);
          const id = (field: string) => `${p.key}-${field}`;
          return (
            <li key={p.key} className="pack" aria-label={`Packaging level ${i + 1}`}>
              <div className="pack__head">
                <span className="pack__summary">
                  {unit && p.quantity && /^\d+$/.test(p.quantity)
                    ? `1 ${unit.uomCode} = ${Number(p.quantity).toLocaleString()} ${baseCode}`
                    : `Level ${i + 1}`}
                </span>
                <button
                  type="button"
                  className="icon-button"
                  aria-label={`Remove packaging level ${i + 1}`}
                  onClick={() => remove(i)}
                >
                  <Trash2 size={16} aria-hidden="true" />
                </button>
              </div>
              <div className="grid grid--3">
                <Field id={id('uom')} label="Unit" error={err('uomId')}>
                  <select id={id('uom')} value={p.uomId} aria-invalid={Boolean(err('uomId'))} onChange={(e) => update(i, 'uomId', e.target.value)}>
                    <option value="">Choose…</option>
                    {choosable.map((u) => (
                      <option key={u.id} value={u.id}>
                        {u.uomCode} · {u.description}
                        {u.status === 'INACTIVE' ? ' (inactive)' : ''}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field id={id('quantity')} label={`${baseCode || 'Eaches'} per unit`} error={err('quantity')}>
                  <input
                    id={id('quantity')}
                    type="number"
                    inputMode="numeric"
                    min="2"
                    step="1"
                    value={p.quantity}
                    aria-invalid={Boolean(err('quantity'))}
                    onChange={(e) => update(i, 'quantity', e.target.value)}
                  />
                </Field>
                <Field id={id('barcode')} label="Barcode" error={err('barcode')}>
                  <input
                    id={id('barcode')}
                    className="input--mono"
                    value={p.barcode}
                    maxLength={50}
                    autoComplete="off"
                    aria-invalid={Boolean(err('barcode'))}
                    onChange={(e) => update(i, 'barcode', e.target.value)}
                  />
                </Field>
              </div>
              <div className="grid grid--6 pack__size">
                {DIMENSIONS.map(({ field, label }) => (
                  <Field key={field} id={id(field)} label={label} error={err(field)}>
                    <input
                      id={id(field)}
                      type="number"
                      inputMode="decimal"
                      min="0.01"
                      step="0.01"
                      value={p[field]}
                      aria-invalid={Boolean(err(field))}
                      onChange={(e) => update(i, field, e.target.value)}
                    />
                  </Field>
                ))}
                <Field id={id('dim-uom')} label="Size unit">
                  <select id={id('dim-uom')} value={p.dimensionUom} onChange={(e) => update(i, 'dimensionUom', e.target.value)}>
                    {DIMENSION_UOMS.map((u) => (
                      <option key={u.value} value={u.value}>
                        {u.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field id={id('weight')} label="Weight" error={err('weight')}>
                  <input
                    id={id('weight')}
                    type="number"
                    inputMode="decimal"
                    min="0.001"
                    step="0.001"
                    value={p.weight}
                    aria-invalid={Boolean(err('weight'))}
                    onChange={(e) => update(i, 'weight', e.target.value)}
                  />
                </Field>
                <Field id={id('weight-uom')} label="Weight unit">
                  <select id={id('weight-uom')} value={p.weightUom} onChange={(e) => update(i, 'weightUom', e.target.value)}>
                    {WEIGHT_UOMS.map((u) => (
                      <option key={u.value} value={u.value}>
                        {u.label}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>
            </li>
          );
        })}
      </ol>

      {packs.length < MAX_PACKS && (
        <button type="button" className="button" onClick={add}>
          <Plus size={16} aria-hidden="true" />
          Add packaging level
        </button>
      )}
    </fieldset>
  );
}
