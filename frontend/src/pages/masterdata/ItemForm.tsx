import { useState, type FormEvent } from 'react';
import {
  ITEM_STATUSES,
  createItem,
  deleteItem,
  updateItem,
  type Item,
  type ItemInput,
  type ItemStatus,
} from '../../api/items';
import { DIMENSION_UOMS, WEIGHT_UOMS, type DimensionUom, type WeightUom } from '../../api/measure';
import { ApiError, errorMessage } from '../../api/http';
import { useOwners } from '../../api/owners';
import { Drawer } from '../../components/Drawer';
import { OwnerSelect } from '../../components/OwnerSelect';
import { Field, sentenceCase } from '../../components/Field';
import { positiveNumberError } from '../../forms/validation';
import type { Uom } from '../../api/uoms';
import { PackagingEditor, packsFromItem, packsToInput, validatePacks, type PackValues } from './PackagingEditor';

interface ItemFormProps {
  /** The item to edit, or null to create a new one. */
  item: Item | null;
  /** All units of measure, for the packaging levels. */
  units: Uom[];
  onClose: () => void;
  onSaved: (item: Item, created: boolean) => void;
  onDeleted: (item: Item) => void;
}

interface FormValues {
  itemId: string;
  description: string;
  status: ItemStatus;
  barcode: string;
  lotControlled: boolean;
  serialControlled: boolean;
  expiryControlled: boolean;
  shelfLifeDays: string;
  width: string;
  depth: string;
  height: string;
  dimensionUom: DimensionUom;
  weight: string;
  weightUom: WeightUom;
}

type Errors = Partial<Record<string, string>>;

const DIMENSIONS = [
  { field: 'width', label: 'Width' },
  { field: 'depth', label: 'Depth' },
  { field: 'height', label: 'Height' },
] as const;

const TRACKING = [
  { field: 'lotControlled', label: 'Lot number', hint: 'Record the lot or batch when received and shipped' },
  { field: 'serialControlled', label: 'Serial number', hint: 'Record a serial number for every unit' },
  { field: 'expiryControlled', label: 'Expiry date', hint: 'Record expiry dates and pick oldest first' },
] as const;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;
const BARCODE_PATTERN = /^[\x21-\x7E]+$/;

export function ItemForm({ item, units, onClose, onSaved, onDeleted }: ItemFormProps) {
  const isNew = item === null;
  const [values, setValues] = useState<FormValues>(() => initialValues(item));
  const [owner, setOwner] = useState('');
  const { several } = useOwners();
  const [packs, setPacks] = useState<PackValues[]>(() => packsFromItem(item?.uoms));
  const originalUnitIds = (item?.uoms ?? []).map((p) => p.uom.id);
  const baseCode = units.find((u) => u.base)?.uomCode ?? 'EA';
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const set = <K extends keyof FormValues>(field: K, value: FormValues[K]) => {
    setValues((v) => ({ ...v, [field]: value }));
    setErrors((current) => {
      const { [field]: _removed, ...rest } = current;
      return rest;
    });
  };

  const validate = (): Errors => {
    const found: Errors = {};
    const id = values.itemId.trim();
    if (!id) found.itemId = 'Enter an item ID';
    else if (!CODE_PATTERN.test(id)) found.itemId = "Use letters, numbers, '-', '_' or '.'";
    if (isNew && several && !owner) found.owner = 'Choose whose it is';
    if (!values.description.trim()) found.description = 'Enter a description';
    const barcode = values.barcode.trim();
    if (barcode && !BARCODE_PATTERN.test(barcode)) found.barcode = 'No spaces or special characters';

    const anyDimension = DIMENSIONS.some(({ field }) => values[field].trim() !== '');
    if (anyDimension) {
      for (const { field } of DIMENSIONS) {
        const message = positiveNumberError(values[field], 2);
        if (message) found[field] = values[field].trim() === '' ? 'Enter all three, or none' : message;
      }
    }
    if (values.weight.trim() !== '') {
      const message = positiveNumberError(values.weight, 3);
      if (message) found.weight = message;
    }
    if (values.expiryControlled && values.shelfLifeDays.trim() !== '') {
      const days = Number(values.shelfLifeDays);
      if (!Number.isInteger(days) || days <= 0) found.shelfLifeDays = 'Whole number of days, greater than 0';
    }
    return { ...found, ...validatePacks(packs, values.barcode) };
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    const anyDimension = DIMENSIONS.some(({ field }) => values[field].trim() !== '');
    const hasWeight = values.weight.trim() !== '';
    const input: ItemInput = {
      owner: isNew && owner ? owner : undefined,
      itemId: values.itemId.trim(),
      description: values.description.trim(),
      status: values.status,
      barcode: values.barcode.trim() || null,
      lotControlled: values.lotControlled,
      serialControlled: values.serialControlled,
      expiryControlled: values.expiryControlled,
      shelfLifeDays: values.expiryControlled && values.shelfLifeDays.trim() ? Number(values.shelfLifeDays) : null,
      width: anyDimension ? Number(values.width) : null,
      depth: anyDimension ? Number(values.depth) : null,
      height: anyDimension ? Number(values.height) : null,
      dimensionUom: anyDimension ? values.dimensionUom : null,
      weight: hasWeight ? Number(values.weight) : null,
      weightUom: hasWeight ? values.weightUom : null,
      uoms: packsToInput(packs),
      version: item?.version,
    };

    setSaving(true);
    try {
      const saved = isNew ? await createItem(input) : await updateItem(item.id, input);
      onSaved(saved, isNew);
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

  const remove = async () => {
    if (!item) return;
    setSaving(true);
    setFormError(null);
    try {
      await deleteItem(item.id);
      onDeleted(item);
    } catch (error) {
      setFormError(errorMessage(error));
      setConfirmingDelete(false);
    } finally {
      setSaving(false);
    }
  };

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {item?.itemId}? This can't be undone.</span>
      <div className="button-row">
        <button type="button" className="button" onClick={() => setConfirmingDelete(false)} disabled={saving}>
          Keep it
        </button>
        <button type="button" className="button button--danger" onClick={remove} disabled={saving}>
          Delete
        </button>
      </div>
    </div>
  ) : (
    <>
      {!isNew && (
        <button type="button" className="button button--danger-quiet" onClick={() => setConfirmingDelete(true)} disabled={saving}>
          Delete
        </button>
      )}
      <div className="button-row button-row--end">
        <button type="button" className="button" onClick={onClose} disabled={saving}>
          Cancel
        </button>
        <button type="submit" form="item-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : isNew ? 'Create item' : 'Save changes'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={isNew ? 'New item' : `Edit ${item.itemId}`} onClose={onClose} footer={footer} wide permission="MASTER_DATA">
      <form id="item-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}

        <div className="grid grid--2">
          <Field id="item-id" label="Item ID" error={errors.itemId}>
            <input
              id="item-id"
              className="input--code"
              value={values.itemId}
              maxLength={40}
              autoComplete="off"
              aria-invalid={Boolean(errors.itemId)}
              onChange={(e) => set('itemId', e.target.value.toUpperCase())}
            />
          </Field>
          <Field id="item-status" label="Status">
            <select id="item-status" value={values.status} onChange={(e) => set('status', e.target.value as ItemStatus)}>
              {ITEM_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
        </div>

        {isNew ? (
          <OwnerSelect id="item-owner" value={owner} onChange={setOwner} error={errors.owner} hint="It can't be changed later" />
        ) : (
          several && item.owner && <p className="muted">Owned by {item.owner.name}.</p>
        )}
        <Field id="item-description" label="Description" error={errors.description}>
          <input
            id="item-description"
            value={values.description}
            maxLength={200}
            aria-invalid={Boolean(errors.description)}
            onChange={(e) => set('description', e.target.value)}
          />
        </Field>

        <Field id="item-barcode" label="Barcode (UPC/EAN/GTIN)" error={errors.barcode} hint="Optional. Scanning it will find this item.">
          <input
            id="item-barcode"
            className="input--mono"
            value={values.barcode}
            maxLength={50}
            autoComplete="off"
            inputMode="numeric"
            aria-invalid={Boolean(errors.barcode)}
            onChange={(e) => set('barcode', e.target.value)}
          />
        </Field>

        <fieldset className="fieldset">
          <legend>Tracking</legend>
          <div className="checks">
            {TRACKING.map(({ field, label, hint }) => (
              <label key={field} className="check">
                <input type="checkbox" checked={values[field]} onChange={(e) => set(field, e.target.checked)} />
                <span>
                  <span className="check__label">{label}</span>
                  <span className="check__hint">{hint}</span>
                </span>
              </label>
            ))}
          </div>
          {values.expiryControlled && (
            <div className="grid grid--2 check__extra">
              <Field id="item-shelf-life" label="Shelf life (days)" error={errors.shelfLifeDays} hint="Optional">
                <input
                  id="item-shelf-life"
                  type="number"
                  inputMode="numeric"
                  min="1"
                  step="1"
                  value={values.shelfLifeDays}
                  aria-invalid={Boolean(errors.shelfLifeDays)}
                  onChange={(e) => set('shelfLifeDays', e.target.value)}
                />
              </Field>
            </div>
          )}
        </fieldset>

        <fieldset className="fieldset">
          <legend>Each dimensions</legend>
          <p className="field__hint">Size of one unit. Optional, but enter all three or none.</p>
          <div className="grid grid--4">
            {DIMENSIONS.map(({ field, label }) => (
              <Field key={field} id={`item-${field}`} label={label} error={errors[field]}>
                <input
                  id={`item-${field}`}
                  type="number"
                  inputMode="decimal"
                  min="0.01"
                  step="0.01"
                  value={values[field]}
                  aria-invalid={Boolean(errors[field])}
                  onChange={(e) => set(field, e.target.value)}
                />
              </Field>
            ))}
            <Field id="item-dim-uom" label="Unit" error={errors.dimensionUom}>
              <select id="item-dim-uom" value={values.dimensionUom} onChange={(e) => set('dimensionUom', e.target.value as DimensionUom)}>
                {DIMENSION_UOMS.map((u) => (
                  <option key={u.value} value={u.value}>
                    {u.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
        </fieldset>

        <fieldset className="fieldset">
          <legend>Each weight</legend>
          <div className="grid grid--4">
            <Field id="item-weight" label="Weight" error={errors.weight}>
              <input
                id="item-weight"
                type="number"
                inputMode="decimal"
                min="0.001"
                step="0.001"
                value={values.weight}
                aria-invalid={Boolean(errors.weight)}
                onChange={(e) => set('weight', e.target.value)}
              />
            </Field>
            <Field id="item-weight-uom" label="Unit" error={errors.weightUom}>
              <select id="item-weight-uom" value={values.weightUom} onChange={(e) => set('weightUom', e.target.value as WeightUom)}>
                {WEIGHT_UOMS.map((u) => (
                  <option key={u.value} value={u.value}>
                    {u.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
        </fieldset>

        <PackagingEditor
          packs={packs}
          onChange={setPacks}
          units={units}
          originalUnitIds={originalUnitIds}
          baseCode={baseCode}
          errors={errors}
          onClearError={(key) =>
            setErrors((current) => {
              const { [key]: _removed, ...rest } = current;
              return rest;
            })
          }
        />
      </form>
    </Drawer>
  );
}


function initialValues(item: Item | null): FormValues {
  const text = (n: number | null | undefined) => (n == null ? '' : String(n));
  return {
    itemId: item?.itemId ?? '',
    description: item?.description ?? '',
    status: item?.status ?? 'ACTIVE',
    barcode: item?.barcode ?? '',
    lotControlled: item?.lotControlled ?? false,
    serialControlled: item?.serialControlled ?? false,
    expiryControlled: item?.expiryControlled ?? false,
    shelfLifeDays: text(item?.shelfLifeDays),
    width: text(item?.width),
    depth: text(item?.depth),
    height: text(item?.height),
    dimensionUom: item?.dimensionUom ?? 'IN',
    weight: text(item?.weight),
    weightUom: item?.weightUom ?? 'LB',
  };
}
