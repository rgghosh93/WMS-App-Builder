import { useRef, useState, type FormEvent } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { CARRIER_MODES, createCarrier, deleteCarrier, updateCarrier, type Carrier, type CarrierInput, type CarrierMode } from '../../api/carriers';
import { ACTIVE_STATUSES, type ActiveStatus } from '../../api/containerTypes';
import { ApiError, errorMessage } from '../../api/http';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';

interface CarrierFormProps {
  /** The carrier to edit, or null to create a new one. */
  carrier: Carrier | null;
  onClose: () => void;
  onSaved: (carrier: Carrier, created: boolean) => void;
  onDeleted: (carrier: Carrier) => void;
}

interface ServiceValues {
  key: string;
  id: string | null;
  serviceCode: string;
  name: string;
  status: ActiveStatus;
}

type Errors = Partial<Record<string, string>>;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export function CarrierForm({ carrier, onClose, onSaved, onDeleted }: CarrierFormProps) {
  const isNew = carrier === null;
  const nextKey = useRef(0);
  const blank = (): ServiceValues => ({ key: `new-${++nextKey.current}`, id: null, serviceCode: '', name: '', status: 'ACTIVE' });

  const [code, setCode] = useState(carrier?.carrierCode ?? '');
  const [name, setName] = useState(carrier?.name ?? '');
  const [mode, setMode] = useState<CarrierMode>(carrier?.mode ?? 'PARCEL');
  const [tracking, setTracking] = useState(carrier?.trackingRequired ?? true);
  const [status, setStatus] = useState<ActiveStatus>(carrier?.status ?? 'ACTIVE');
  const [services, setServices] = useState<ServiceValues[]>(() => (carrier ? carrier.services.map((s) => ({ ...s, key: s.id })) : [blank()]));
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const clear = (key: string) => setErrors((e) => ({ ...e, [key]: undefined }));

  const updateService = (index: number, changes: Partial<ServiceValues>) => {
    setServices((current) => current.map((s, i) => (i === index ? { ...s, ...changes } : s)));
    Object.keys(changes).forEach((k) => clear(`services[${index}].${k}`));
  };

  const validate = (): Errors => {
    const found: Errors = {};
    const codeError = (value: string) =>
      !value.trim() ? 'Enter a code' : !CODE_PATTERN.test(value.trim()) ? "Use letters, numbers, '-', '_' or '.'" : undefined;
    if (codeError(code)) found.carrierCode = codeError(code);
    if (!name.trim()) found.name = 'Enter a name';
    if (services.length === 0) found.services = 'Add at least one service';
    services.forEach((s, i) => {
      const problem = codeError(s.serviceCode);
      if (problem) found[`services[${i}].serviceCode`] = problem;
      if (!s.name.trim()) found[`services[${i}].name`] = 'Enter a name';
    });
    return found;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    const input: CarrierInput = {
      carrierCode: code.trim(),
      name: name.trim(),
      mode,
      trackingRequired: tracking,
      status,
      services: services.map((s) => ({ id: s.id, serviceCode: s.serviceCode.trim(), name: s.name.trim(), status: s.status })),
      version: carrier?.version,
    };
    setSaving(true);
    try {
      onSaved(isNew ? await createCarrier(input) : await updateCarrier(carrier.id, input), isNew);
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
      }
      setFormError(errorMessage(error));
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!carrier) return;
    setSaving(true);
    setFormError(null);
    try {
      await deleteCarrier(carrier.id);
      onDeleted(carrier);
    } catch (error) {
      setFormError(errorMessage(error));
      setConfirmingDelete(false);
      setSaving(false);
    }
  };

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {carrier?.carrierCode}? This can't be undone.</span>
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
        <button type="submit" form="carrier-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : isNew ? 'Create carrier' : 'Save changes'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={isNew ? 'New carrier' : `Edit ${carrier.carrierCode}`} onClose={onClose} footer={footer} wide permission="MASTER_DATA">
      <form id="carrier-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <div className="grid grid--2">
          <Field id="carrier-code" label="Code" error={errors.carrierCode}>
            <input
              id="carrier-code"
              className="input--code"
              value={code}
              maxLength={20}
              autoComplete="off"
              aria-invalid={Boolean(errors.carrierCode)}
              onChange={(e) => {
                setCode(e.target.value.toUpperCase());
                clear('carrierCode');
              }}
            />
          </Field>
          <Field id="carrier-name" label="Name" error={errors.name}>
            <input
              id="carrier-name"
              value={name}
              maxLength={120}
              aria-invalid={Boolean(errors.name)}
              onChange={(e) => {
                setName(e.target.value);
                clear('name');
              }}
            />
          </Field>
          <Field id="carrier-mode" label="Mode">
            <select id="carrier-mode" value={mode} onChange={(e) => setMode(e.target.value as CarrierMode)}>
              {CARRIER_MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </Field>
          <Field id="carrier-status" label="Status">
            <select id="carrier-status" value={status} onChange={(e) => setStatus(e.target.value as ActiveStatus)}>
              {ACTIVE_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <label className="check">
          <input type="checkbox" checked={tracking} onChange={(e) => setTracking(e.target.checked)} />
          <span>
            <span className="check__label">Tracking number required</span>
            <span className="check__hint">Every carton needs the carrier's tracking number before it can be loaded.</span>
          </span>
        </label>

        <fieldset className="fieldset">
          <legend>Services</legend>
          {errors.services && (
            <p className="field__error" role="alert">
              {errors.services}
            </p>
          )}
          {services.map((s, i) => (
            <div key={s.key} className="service-row" role="group" aria-label={`Service ${i + 1}`}>
              <Field id={`${s.key}-code`} label="Code" error={errors[`services[${i}].serviceCode`]}>
                <input
                  id={`${s.key}-code`}
                  className="input--code"
                  value={s.serviceCode}
                  maxLength={20}
                  autoComplete="off"
                  onChange={(e) => updateService(i, { serviceCode: e.target.value.toUpperCase() })}
                />
              </Field>
              <Field id={`${s.key}-name`} label="Name" error={errors[`services[${i}].name`]}>
                <input id={`${s.key}-name`} value={s.name} maxLength={120} onChange={(e) => updateService(i, { name: e.target.value })} />
              </Field>
              <Field id={`${s.key}-status`} label="Status">
                <select id={`${s.key}-status`} value={s.status} onChange={(e) => updateService(i, { status: e.target.value as ActiveStatus })}>
                  {ACTIVE_STATUSES.map((a) => (
                    <option key={a.value} value={a.value}>
                      {a.label}
                    </option>
                  ))}
                </select>
              </Field>
              <button
                type="button"
                className="icon-button"
                aria-label={`Remove service ${i + 1}`}
                onClick={() => {
                  setServices((current) => current.filter((_, index) => index !== i));
                  setErrors({});
                }}
              >
                <Trash2 size={16} aria-hidden="true" />
              </button>
            </div>
          ))}
          <button type="button" className="button" onClick={() => setServices((current) => [...current, blank()])}>
            <Plus size={16} aria-hidden="true" />
            Add service
          </button>
        </fieldset>
      </form>
    </Drawer>
  );
}
