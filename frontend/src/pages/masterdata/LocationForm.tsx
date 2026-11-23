import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  DIMENSION_UOMS,
  IDENTIFIER_FIELDS,
  IDENTIFIER_LABELS,
  createLocation,
  deleteLocation,
  updateLocation,
  type DimensionUom,
  type IdentifierField,
  type Location,
  type LocationInput,
  type LocationType,
  type LocationTypeInfo,
} from '../../api/locations';
import {
  VOLUME_UOMS,
  WEIGHT_UOMS,
  formatNumber,
  physicalVolume,
  type VolumeUom,
  type WeightUom,
} from '../../api/measure';
import { ApiError, errorMessage } from '../../api/http';
import { listLocationZones, type ZoneOfLocation } from '../../api/zones';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';
import { optionalNumber, positiveIntegerError, positiveNumberError } from '../../forms/validation';

interface LocationFormProps {
  /** The location to edit, or null to create a new one. */
  location: Location | null;
  types: LocationTypeInfo[];
  onClose: () => void;
  onSaved: (location: Location, created: boolean) => void;
  onDeleted: (location: Location) => void;
}

interface FormValues {
  type: LocationType;
  locationId: string;
  identifiers: Record<IdentifierField, string>;
  width: string;
  depth: string;
  height: string;
  dimensionUom: DimensionUom;
  maxWeight: string;
  maxWeightUom: WeightUom;
  maxVolume: string;
  maxVolumeUom: VolumeUom;
  maxLpns: string;
  maxSkus: string;
  maxUnits: string;
}

type Errors = Partial<Record<string, string>>;

const DIMENSIONS = [
  { field: 'width', label: 'Width' },
  { field: 'depth', label: 'Depth' },
  { field: 'height', label: 'Height' },
] as const;

const COUNT_LIMITS = [
  { field: 'maxLpns', label: 'Max LPNs', hint: 'Pallets or containers' },
  { field: 'maxSkus', label: 'Max SKUs', hint: 'Different items' },
  { field: 'maxUnits', label: 'Max units', hint: 'Total eaches' },
] as const;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export function LocationForm({ location, types, onClose, onSaved, onDeleted }: LocationFormProps) {
  const isNew = location === null;
  const [values, setValues] = useState<FormValues>(() => initialValues(location, types));
  // For new locations the ID follows the identifiers until the user types their own.
  const [idTouched, setIdTouched] = useState(!isNew);
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const typeInfo = types.find((t) => t.type === values.type);
  const required = useMemo(
    () => IDENTIFIER_FIELDS.filter((f) => typeInfo?.requiredIdentifiers.includes(f)),
    [typeInfo],
  );

  const setIdentifier = (field: IdentifierField, raw: string) => {
    const value = raw.toUpperCase();
    setValues((current) => {
      const identifiers = { ...current.identifiers, [field]: value };
      const locationId = idTouched ? current.locationId : suggestId(required, identifiers);
      return { ...current, identifiers, locationId };
    });
    clearError(field, 'identifiers', idTouched ? '' : 'locationId');
  };

  const clearError = (...fields: string[]) =>
    setErrors((current) => {
      const next = { ...current };
      fields.forEach((f) => delete next[f]);
      return next;
    });

  const validate = (): Errors => {
    const found: Errors = {};
    const id = values.locationId.trim();
    if (!id) found.locationId = 'Enter a location ID';
    else if (!CODE_PATTERN.test(id)) found.locationId = "Use letters, numbers, '-', '_' or '.'";
    for (const field of required) {
      const value = values.identifiers[field].trim();
      if (!value) found[field] = 'Required';
      else if (!CODE_PATTERN.test(value)) found[field] = 'Letters, numbers, - _ .';
    }
    for (const { field, label } of DIMENSIONS) {
      const n = Number(values[field]);
      if (values[field].trim() === '' || Number.isNaN(n)) found[field] = `Enter the ${label.toLowerCase()}`;
      else if (n <= 0) found[field] = 'Must be greater than 0';
      else if (!/^\d+(\.\d{1,2})?$/.test(values[field].trim())) found[field] = 'Up to 2 decimal places';
    }
    // Capacity limits are optional: only check what was entered.
    if (values.maxWeight.trim()) found.maxWeight = positiveNumberError(values.maxWeight, 3) ?? undefined;
    if (values.maxVolume.trim()) found.maxVolume = positiveNumberError(values.maxVolume, 3) ?? undefined;
    for (const { field } of COUNT_LIMITS) {
      if (values[field].trim()) found[field] = positiveIntegerError(values[field]) ?? undefined;
    }
    return Object.fromEntries(Object.entries(found).filter(([, message]) => message)) as Errors;
  };

  const physical = physicalVolume(
    Number(values.width),
    Number(values.depth),
    Number(values.height),
    values.dimensionUom,
    values.maxVolumeUom,
  );
  const volumeUnitLabel = VOLUME_UOMS.find((u) => u.value === values.maxVolumeUom)?.label;

  const setValue = <K extends keyof FormValues>(field: K, value: FormValues[K]) => {
    setValues((v) => ({ ...v, [field]: value }));
    clearError(field);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    const input: LocationInput = {
      type: values.type,
      locationId: values.locationId.trim(),
      area: '',
      zone: '',
      aisle: '',
      bay: '',
      level: '',
      position: '',
      ...Object.fromEntries(required.map((f) => [f, values.identifiers[f].trim()])),
      width: Number(values.width),
      depth: Number(values.depth),
      height: Number(values.height),
      dimensionUom: values.dimensionUom,
      maxWeight: optionalNumber(values.maxWeight),
      maxWeightUom: values.maxWeight.trim() ? values.maxWeightUom : null,
      maxVolume: optionalNumber(values.maxVolume),
      maxVolumeUom: values.maxVolume.trim() ? values.maxVolumeUom : null,
      maxLpns: optionalNumber(values.maxLpns),
      maxSkus: optionalNumber(values.maxSkus),
      maxUnits: optionalNumber(values.maxUnits),
      version: location?.version,
    };

    setSaving(true);
    try {
      const saved = isNew ? await createLocation(input) : await updateLocation(location.id, input);
      onSaved(saved, isNew);
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(
          Object.fromEntries(Object.entries(error.fieldErrors).map(([field, message]) => [field, sentenceCase(message)])),
        );
        setFormError(error.message);
      } else {
        setFormError(errorMessage(error));
      }
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!location) return;
    setSaving(true);
    setFormError(null);
    try {
      await deleteLocation(location.id);
      onDeleted(location);
    } catch (error) {
      setFormError(errorMessage(error));
      setConfirmingDelete(false);
    } finally {
      setSaving(false);
    }
  };

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {location?.locationId}? This can't be undone.</span>
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
        <button type="submit" form="location-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : isNew ? 'Create location' : 'Save changes'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={isNew ? 'New location' : `Edit ${location.locationId}`} onClose={onClose} footer={footer} permission="MASTER_DATA">
      <form id="location-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}

        <div className="field">
          <label htmlFor="loc-type">Location type</label>
          <select
            id="loc-type"
            value={values.type}
            onChange={(e) => setValues((v) => ({ ...v, type: e.target.value as LocationType }))}
          >
            {types.map((t) => (
              <option key={t.type} value={t.type}>
                {t.label}
              </option>
            ))}
          </select>
        </div>

        <fieldset className="fieldset">
          <legend>Identifiers</legend>
          <p className="field__hint">Together these must be unique for each {typeInfo?.label ?? ''} location.</p>
          <div className="grid grid--3">
            {required.map((field) => (
              <Field key={field} id={`loc-${field}`} label={IDENTIFIER_LABELS[field]} error={errors[field]}>
                <input
                  id={`loc-${field}`}
                  className="input--code"
                  value={values.identifiers[field]}
                  maxLength={20}
                  autoComplete="off"
                  aria-invalid={Boolean(errors[field])}
                  onChange={(e) => setIdentifier(field, e.target.value)}
                />
              </Field>
            ))}
          </div>
          {errors.identifiers && (
            <p className="field__error" role="alert">
              {errors.identifiers}
            </p>
          )}
        </fieldset>

        <Field
          id="loc-id"
          label="Location ID"
          error={errors.locationId}
          hint={isNew && !idTouched ? 'Filled in from the identifiers until you change it.' : undefined}
        >
          <input
            id="loc-id"
            className="input--code"
            value={values.locationId}
            maxLength={40}
            autoComplete="off"
            aria-invalid={Boolean(errors.locationId)}
            onChange={(e) => {
              setIdTouched(true);
              setValues((v) => ({ ...v, locationId: e.target.value.toUpperCase() }));
              clearError('locationId');
            }}
          />
        </Field>

        <fieldset className="fieldset">
          <legend>Dimensions</legend>
          <div className="grid grid--4">
            {DIMENSIONS.map(({ field, label }) => (
              <Field key={field} id={`loc-${field}`} label={label} error={errors[field]}>
                <input
                  id={`loc-${field}`}
                  type="number"
                  inputMode="decimal"
                  min="0.01"
                  step="0.01"
                  value={values[field]}
                  aria-invalid={Boolean(errors[field])}
                  onChange={(e) => {
                    setValues((v) => ({ ...v, [field]: e.target.value }));
                    clearError(field);
                  }}
                />
              </Field>
            ))}
            <Field id="loc-uom" label="Unit">
              <select
                id="loc-uom"
                value={values.dimensionUom}
                onChange={(e) => setValues((v) => ({ ...v, dimensionUom: e.target.value as DimensionUom }))}
              >
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
          <legend>Capacity</legend>
          <p className="field__hint">Optional limits. Leave a box blank for no limit.</p>
          <div className="grid grid--4">
            <Field id="loc-max-weight" label="Max weight" error={errors.maxWeight}>
              <input
                id="loc-max-weight"
                type="number"
                inputMode="decimal"
                min="0.001"
                step="0.001"
                value={values.maxWeight}
                aria-invalid={Boolean(errors.maxWeight)}
                onChange={(e) => setValue('maxWeight', e.target.value)}
              />
            </Field>
            <Field id="loc-max-weight-uom" label="Weight unit" error={errors.maxWeightUom}>
              <select
                id="loc-max-weight-uom"
                value={values.maxWeightUom}
                onChange={(e) => setValue('maxWeightUom', e.target.value as WeightUom)}
              >
                {WEIGHT_UOMS.map((u) => (
                  <option key={u.value} value={u.value}>
                    {u.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field id="loc-max-volume" label="Max volume" error={errors.maxVolume}>
              <input
                id="loc-max-volume"
                type="number"
                inputMode="decimal"
                min="0.001"
                step="0.001"
                value={values.maxVolume}
                aria-invalid={Boolean(errors.maxVolume)}
                onChange={(e) => setValue('maxVolume', e.target.value)}
              />
            </Field>
            <Field id="loc-max-volume-uom" label="Volume unit" error={errors.maxVolumeUom}>
              <select
                id="loc-max-volume-uom"
                value={values.maxVolumeUom}
                onChange={(e) => setValue('maxVolumeUom', e.target.value as VolumeUom)}
              >
                {VOLUME_UOMS.map((u) => (
                  <option key={u.value} value={u.value}>
                    {u.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          {physical != null && (
            <p className="field__hint capacity__physical">
              Physical space from the dimensions: {formatNumber(physical)} {volumeUnitLabel}.{' '}
              <button type="button" className="link-button link-button--small" onClick={() => setValue('maxVolume', String(physical))}>
                Use as max volume
              </button>
            </p>
          )}
          <div className="grid grid--3 capacity__counts">
            {COUNT_LIMITS.map(({ field, label, hint }) => (
              <Field key={field} id={`loc-${field}`} label={label} error={errors[field]} hint={hint}>
                <input
                  id={`loc-${field}`}
                  type="number"
                  inputMode="numeric"
                  min="1"
                  step="1"
                  value={values[field]}
                  aria-invalid={Boolean(errors[field])}
                  onChange={(e) => setValue(field, e.target.value)}
                />
              </Field>
            ))}
          </div>
        </fieldset>
        {location && <LocationZones locationId={location.id} />}
      </form>
    </Drawer>
  );
}

/** The zones a location is in, by function. Not the zone in its address. */
function LocationZones({ locationId }: { locationId: string }) {
  const [zones, setZones] = useState<ZoneOfLocation[] | null>(null);
  const [failed, setFailed] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listLocationZones(locationId, controller.signal)
      .then(setZones)
      .catch((e) => {
        if (!controller.signal.aborted) setFailed(errorMessage(e));
      });
    return () => controller.abort();
  }, [locationId]);

  return (
    <fieldset className="fieldset">
      <legend>In zones</legend>
      <p className="field__hint">
        {failed ?? (!zones ? 'Loading…' : zones.length === 0 ? 'In none. ' : '')}
        {zones && (
          <>
            Change them under <Link to="/master-data/zones">Zones</Link>.
          </>
        )}
      </p>
      {zones && zones.length > 0 && (
        <p className="tag-list tag-list--wrap">
          {zones.map((z) => (
            <span key={z.zoneId} className="tag">
              {z.label}:{' '}
              <Link className="code" to={`/master-data/zones/${z.zoneId}`} title={z.name}>
                {z.code}
              </Link>
              {z.status === 'INACTIVE' && ' (inactive)'}
            </span>
          ))}
        </p>
      )}
    </fieldset>
  );
}

function initialValues(location: Location | null, types: LocationTypeInfo[]): FormValues {
  const identifiers = Object.fromEntries(
    IDENTIFIER_FIELDS.map((f) => [f, location?.[f] ?? '']),
  ) as Record<IdentifierField, string>;
  return {
    type: location?.type ?? types[0]?.type ?? 'STORAGE',
    locationId: location?.locationId ?? '',
    identifiers,
    width: location ? String(location.width) : '',
    depth: location ? String(location.depth) : '',
    height: location ? String(location.height) : '',
    dimensionUom: location?.dimensionUom ?? 'IN',
    maxWeight: text(location?.maxWeight),
    maxWeightUom: location?.maxWeightUom ?? 'LB',
    maxVolume: text(location?.maxVolume),
    maxVolumeUom: location?.maxVolumeUom ?? 'CU_FT',
    maxLpns: text(location?.maxLpns),
    maxSkus: text(location?.maxSkus),
    maxUnits: text(location?.maxUnits),
  };
}

function text(n: number | null | undefined): string {
  return n == null ? '' : String(n);
}


function suggestId(fields: IdentifierField[], identifiers: Record<IdentifierField, string>): string {
  return fields
    .map((f) => identifiers[f].trim())
    .filter(Boolean)
    .join('-');
}
