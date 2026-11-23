import { useState, type FormEvent } from 'react';
import {
  ACTIVE_STATUSES,
  CONTAINER_CATEGORIES,
  createContainerType,
  deleteContainerType,
  updateContainerType,
  type ActiveStatus,
  type ContainerCategory,
  type ContainerType,
  type ContainerTypeInput,
} from '../../api/containerTypes';
import {
  DIMENSION_UOMS,
  VOLUME_UOMS,
  WEIGHT_UOMS,
  formatNumber,
  physicalVolume,
  type DimensionUom,
  type VolumeUom,
  type WeightUom,
} from '../../api/measure';
import { ApiError, errorMessage } from '../../api/http';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';
import { optionalNumber, positiveNumberError } from '../../forms/validation';

interface ContainerTypeFormProps {
  /** The container type to edit, or null to create a new one. */
  containerType: ContainerType | null;
  onClose: () => void;
  onSaved: (containerType: ContainerType, created: boolean) => void;
  onDeleted: (containerType: ContainerType) => void;
}

interface FormValues {
  containerTypeId: string;
  description: string;
  category: ContainerCategory;
  status: ActiveStatus;
  reusable: boolean;
  width: string;
  depth: string;
  height: string;
  dimensionUom: DimensionUom;
  tareWeight: string;
  tareWeightUom: WeightUom;
  maxWeight: string;
  maxWeightUom: WeightUom;
  maxVolume: string;
  maxVolumeUom: VolumeUom;
}

type Errors = Partial<Record<string, string>>;

const DIMENSIONS = [
  { field: 'width', label: 'Width' },
  { field: 'depth', label: 'Depth' },
  { field: 'height', label: 'Height' },
] as const;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export function ContainerTypeForm({ containerType, onClose, onSaved, onDeleted }: ContainerTypeFormProps) {
  const isNew = containerType === null;
  const [values, setValues] = useState<FormValues>(() => initialValues(containerType));
  // For new types, "reusable" follows the category until the user ticks or unticks it.
  const [reusableTouched, setReusableTouched] = useState(!isNew);
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

  const setCategory = (category: ContainerCategory) => {
    setValues((v) => ({
      ...v,
      category,
      reusable: reusableTouched ? v.reusable : (CONTAINER_CATEGORIES.find((c) => c.value === category)?.reusableByDefault ?? false),
    }));
  };

  const validate = (): Errors => {
    const found: Errors = {};
    const id = values.containerTypeId.trim();
    if (!id) found.containerTypeId = 'Enter a container type ID';
    else if (!CODE_PATTERN.test(id)) found.containerTypeId = "Use letters, numbers, '-', '_' or '.'";
    if (!values.description.trim()) found.description = 'Enter a description';
    for (const { field } of DIMENSIONS) {
      const message = positiveNumberError(values[field], 2);
      if (message) found[field] = message;
    }
    for (const field of ['tareWeight', 'maxWeight', 'maxVolume'] as const) {
      if (values[field].trim()) {
        const message = positiveNumberError(values[field], 3);
        if (message) found[field] = message;
      }
    }
    return found;
  };

  const outerVolume = physicalVolume(
    Number(values.width),
    Number(values.depth),
    Number(values.height),
    values.dimensionUom,
    values.maxVolumeUom,
  );
  const volumeUnitLabel = VOLUME_UOMS.find((u) => u.value === values.maxVolumeUom)?.label;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    const input: ContainerTypeInput = {
      containerTypeId: values.containerTypeId.trim(),
      description: values.description.trim(),
      category: values.category,
      status: values.status,
      reusable: values.reusable,
      width: Number(values.width),
      depth: Number(values.depth),
      height: Number(values.height),
      dimensionUom: values.dimensionUom,
      tareWeight: optionalNumber(values.tareWeight),
      tareWeightUom: values.tareWeight.trim() ? values.tareWeightUom : null,
      maxWeight: optionalNumber(values.maxWeight),
      maxWeightUom: values.maxWeight.trim() ? values.maxWeightUom : null,
      maxVolume: optionalNumber(values.maxVolume),
      maxVolumeUom: values.maxVolume.trim() ? values.maxVolumeUom : null,
      version: containerType?.version,
    };

    setSaving(true);
    try {
      const saved = isNew ? await createContainerType(input) : await updateContainerType(containerType.id, input);
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
    if (!containerType) return;
    setSaving(true);
    setFormError(null);
    try {
      await deleteContainerType(containerType.id);
      onDeleted(containerType);
    } catch (error) {
      setFormError(errorMessage(error));
      setConfirmingDelete(false);
    } finally {
      setSaving(false);
    }
  };

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {containerType?.containerTypeId}? This can't be undone.</span>
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
        <button type="submit" form="container-type-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : isNew ? 'Create container type' : 'Save changes'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={isNew ? 'New container type' : `Edit ${containerType.containerTypeId}`} onClose={onClose} footer={footer} permission="MASTER_DATA">
      <form id="container-type-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}

        <div className="grid grid--2">
          <Field id="ct-id" label="Container type ID" error={errors.containerTypeId}>
            <input
              id="ct-id"
              className="input--code"
              value={values.containerTypeId}
              maxLength={40}
              autoComplete="off"
              aria-invalid={Boolean(errors.containerTypeId)}
              onChange={(e) => set('containerTypeId', e.target.value.toUpperCase())}
            />
          </Field>
          <Field id="ct-category" label="Category">
            <select id="ct-category" value={values.category} onChange={(e) => setCategory(e.target.value as ContainerCategory)}>
              {CONTAINER_CATEGORIES.map((c) => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <Field id="ct-description" label="Description" error={errors.description}>
          <input
            id="ct-description"
            value={values.description}
            maxLength={200}
            aria-invalid={Boolean(errors.description)}
            onChange={(e) => set('description', e.target.value)}
          />
        </Field>

        <div className="grid grid--2">
          <Field id="ct-status" label="Status">
            <select id="ct-status" value={values.status} onChange={(e) => set('status', e.target.value as ActiveStatus)}>
              {ACTIVE_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          <label className="check check--inline">
            <input
              type="checkbox"
              checked={values.reusable}
              onChange={(e) => {
                setReusableTouched(true);
                set('reusable', e.target.checked);
              }}
            />
            <span>
              <span className="check__label">Reusable</span>
              <span className="check__hint">Comes back for reuse, rather than shipping out</span>
            </span>
          </label>
        </div>

        <fieldset className="fieldset">
          <legend>Outer dimensions</legend>
          <div className="grid grid--4">
            {DIMENSIONS.map(({ field, label }) => (
              <Field key={field} id={`ct-${field}`} label={label} error={errors[field]}>
                <input
                  id={`ct-${field}`}
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
            <Field id="ct-dim-uom" label="Unit">
              <select id="ct-dim-uom" value={values.dimensionUom} onChange={(e) => set('dimensionUom', e.target.value as DimensionUom)}>
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
          <legend>Weight and capacity</legend>
          <p className="field__hint">Optional. Tare is the empty container's own weight.</p>
          <div className="grid grid--4">
            <Field id="ct-tare" label="Tare weight" error={errors.tareWeight}>
              <input
                id="ct-tare"
                type="number"
                inputMode="decimal"
                min="0.001"
                step="0.001"
                value={values.tareWeight}
                aria-invalid={Boolean(errors.tareWeight)}
                onChange={(e) => set('tareWeight', e.target.value)}
              />
            </Field>
            <Field id="ct-tare-uom" label="Tare unit">
              <select id="ct-tare-uom" value={values.tareWeightUom} onChange={(e) => set('tareWeightUom', e.target.value as WeightUom)}>
                {WEIGHT_UOMS.map((u) => (
                  <option key={u.value} value={u.value}>
                    {u.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field id="ct-max-weight" label="Max load" error={errors.maxWeight}>
              <input
                id="ct-max-weight"
                type="number"
                inputMode="decimal"
                min="0.001"
                step="0.001"
                value={values.maxWeight}
                aria-invalid={Boolean(errors.maxWeight)}
                onChange={(e) => set('maxWeight', e.target.value)}
              />
            </Field>
            <Field id="ct-max-weight-uom" label="Load unit">
              <select id="ct-max-weight-uom" value={values.maxWeightUom} onChange={(e) => set('maxWeightUom', e.target.value as WeightUom)}>
                {WEIGHT_UOMS.map((u) => (
                  <option key={u.value} value={u.value}>
                    {u.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field id="ct-max-volume" label="Max volume" error={errors.maxVolume}>
              <input
                id="ct-max-volume"
                type="number"
                inputMode="decimal"
                min="0.001"
                step="0.001"
                value={values.maxVolume}
                aria-invalid={Boolean(errors.maxVolume)}
                onChange={(e) => set('maxVolume', e.target.value)}
              />
            </Field>
            <Field id="ct-max-volume-uom" label="Volume unit">
              <select id="ct-max-volume-uom" value={values.maxVolumeUom} onChange={(e) => set('maxVolumeUom', e.target.value as VolumeUom)}>
                {VOLUME_UOMS.map((u) => (
                  <option key={u.value} value={u.value}>
                    {u.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          {outerVolume != null && (
            <p className="field__hint capacity__physical">
              Outer volume from the dimensions: {formatNumber(outerVolume)} {volumeUnitLabel}.{' '}
              <button type="button" className="link-button link-button--small" onClick={() => set('maxVolume', String(outerVolume))}>
                Use as max volume
              </button>
            </p>
          )}
        </fieldset>
      </form>
    </Drawer>
  );
}

function initialValues(c: ContainerType | null): FormValues {
  const text = (n: number | null | undefined) => (n == null ? '' : String(n));
  return {
    containerTypeId: c?.containerTypeId ?? '',
    description: c?.description ?? '',
    category: c?.category ?? 'PALLET',
    status: c?.status ?? 'ACTIVE',
    reusable: c?.reusable ?? true,
    width: text(c?.width),
    depth: text(c?.depth),
    height: text(c?.height),
    dimensionUom: c?.dimensionUom ?? 'IN',
    tareWeight: text(c?.tareWeight),
    tareWeightUom: c?.tareWeightUom ?? 'LB',
    maxWeight: text(c?.maxWeight),
    maxWeightUom: c?.maxWeightUom ?? 'LB',
    maxVolume: text(c?.maxVolume),
    maxVolumeUom: c?.maxVolumeUom ?? 'CU_FT',
  };
}
