import { useState, type FormEvent } from 'react';
import {
  CONTAINER_STATUSES,
  MAX_RANGE,
  createContainerRange,
  rangeLpn,
  type ContainerRangeResult,
  type ContainerStatus,
  type LocationRef,
} from '../../api/containers';
import { categoryLabel, type ContainerType } from '../../api/containerTypes';
import { ApiError, errorMessage } from '../../api/http';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';
import { LocationPicker } from '../../components/LocationPicker';

interface ContainerRangeFormProps {
  types: ContainerType[];
  onClose: () => void;
  onCreated: (result: ContainerRangeResult) => void;
}

type Errors = Partial<Record<string, string>>;

const PREFIX_PATTERN = /^([A-Za-z0-9][A-Za-z0-9._-]*)?$/;

/** Creates a numbered run of containers, e.g. TOTE00001 to TOTE00500. */
export function ContainerRangeForm({ types, onClose, onCreated }: ContainerRangeFormProps) {
  const [prefix, setPrefix] = useState('');
  const [start, setStart] = useState('1');
  const [count, setCount] = useState('');
  const [digits, setDigits] = useState('5');
  const [containerTypeId, setContainerTypeId] = useState('');
  const [status, setStatus] = useState<ContainerStatus>('AVAILABLE');
  const [location, setLocation] = useState<LocationRef | null>(null);
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const activeTypes = types.filter((t) => t.status === 'ACTIVE');
  const startN = Number(start);
  const countN = Number(count);
  const digitsN = Number(digits);
  const numbersOk = /^\d+$/.test(start) && /^\d+$/.test(count) && countN >= 1 && /^\d+$/.test(digits) && digitsN >= 1;
  const preview = numbersOk
    ? countN === 1
      ? rangeLpn(prefix, startN, digitsN)
      : `${rangeLpn(prefix, startN, digitsN)} to ${rangeLpn(prefix, startN + countN - 1, digitsN)}`
    : null;

  const validate = (): Errors => {
    const found: Errors = {};
    if (!PREFIX_PATTERN.test(prefix.trim())) found.prefix = "Use letters, numbers, '-', '_' or '.'";
    if (!/^\d+$/.test(start)) found.start = 'Whole number, 0 or more';
    if (!/^\d+$/.test(count) || countN < 1) found.count = 'Whole number, at least 1';
    else if (countN > MAX_RANGE) found.count = `At most ${MAX_RANGE.toLocaleString()} at a time`;
    if (!/^\d+$/.test(digits) || digitsN < 1 || digitsN > 10) found.digits = '1 to 10';
    else if (numbersOk && String(startN + countN - 1).length > digitsN) {
      found.digits = `Needs at least ${String(startN + countN - 1).length} to reach ${startN + countN - 1}`;
    }
    if (!containerTypeId) found.containerTypeId = 'Choose a container type';
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
      const result = await createContainerRange({
        prefix: prefix.trim(),
        start: startN,
        count: countN,
        digits: digitsN,
        containerTypeId,
        status,
        currentLocationId: location?.id ?? null,
        notes: null,
      });
      onCreated(result);
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

  const footer = (
    <div className="button-row button-row--end">
      <button type="button" className="button" onClick={onClose} disabled={saving}>
        Cancel
      </button>
      <button type="submit" form="container-range-form" className="button button--primary" disabled={saving}>
        {saving ? 'Creating…' : numbersOk && countN > 1 ? `Create ${countN.toLocaleString()} containers` : 'Create containers'}
      </button>
    </div>
  );

  return (
    <Drawer title="Add a range of containers" onClose={onClose} footer={footer}>
      <form id="container-range-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}

        <fieldset className="fieldset">
          <legend>LPNs</legend>
          <p className="field__hint">Each LPN is the prefix followed by a zero-padded number.</p>
          <div className="grid grid--4">
            <Field id="range-prefix" label="Prefix" error={errors.prefix}>
              <input
                id="range-prefix"
                className="input--code"
                value={prefix}
                maxLength={30}
                autoComplete="off"
                onChange={(e) => setPrefix(e.target.value.toUpperCase())}
              />
            </Field>
            <Field id="range-start" label="First number" error={errors.start}>
              <input id="range-start" type="number" inputMode="numeric" min="0" value={start} onChange={(e) => setStart(e.target.value)} />
            </Field>
            <Field id="range-count" label="How many" error={errors.count}>
              <input
                id="range-count"
                type="number"
                inputMode="numeric"
                min="1"
                max={MAX_RANGE}
                value={count}
                onChange={(e) => setCount(e.target.value)}
              />
            </Field>
            <Field id="range-digits" label="Digits" error={errors.digits}>
              <input id="range-digits" type="number" inputMode="numeric" min="1" max="10" value={digits} onChange={(e) => setDigits(e.target.value)} />
            </Field>
          </div>
          {preview && (
            <p className="range__preview" aria-live="polite">
              Will create <span className="code">{preview}</span>
            </p>
          )}
        </fieldset>

        <Field id="range-type" label="Container type" error={errors.containerTypeId}>
          <select id="range-type" value={containerTypeId} onChange={(e) => setContainerTypeId(e.target.value)}>
            <option value="">Choose a type…</option>
            {activeTypes.map((t) => (
              <option key={t.id} value={t.id}>
                {t.containerTypeId} · {t.description} ({categoryLabel(t.category)})
              </option>
            ))}
          </select>
        </Field>

        <Field id="range-status" label="Status">
          <select id="range-status" value={status} onChange={(e) => setStatus(e.target.value as ContainerStatus)}>
            {CONTAINER_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </Field>

        <Field id="range-location" label="Current location" error={errors.currentLocationId} hint="Optional. Puts every new container at this location.">
          <LocationPicker id="range-location" value={location} onChange={setLocation} />
        </Field>
      </form>
    </Drawer>
  );
}
