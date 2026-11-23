import { useState, type FormEvent } from 'react';
import { ACTIVE_STATUSES, type ActiveStatus } from '../../api/containerTypes';
import { errorMessage } from '../../api/http';
import { ZONE_FUNCTIONS, createZone, deleteZone, updateZone, zoneFunctionLabel, type Zone, type ZoneFunction } from '../../api/zones';
import { Drawer } from '../../components/Drawer';
import { Field } from '../../components/Field';
import { useSubmit } from '../inventory/control/useSubmit';

interface ZoneFormProps {
  /** The zone to edit, or null to create a new one. */
  zone: Zone | null;
  initialFunction?: ZoneFunction;
  onClose: () => void;
  onSaved: (zone: Zone) => void;
  onDeleted?: () => void;
}

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export function ZoneForm({ zone, initialFunction = 'ALLOCATION', onClose, onSaved, onDeleted }: ZoneFormProps) {
  const [fn, setFn] = useState<ZoneFunction>(zone?.function ?? initialFunction);
  const [code, setCode] = useState(zone?.code ?? '');
  const [name, setName] = useState(zone?.name ?? '');
  const [status, setStatus] = useState<ActiveStatus>(zone?.status ?? 'ACTIVE');
  const [priority, setPriority] = useState(zone?.priority == null ? '' : String(zone.priority));
  const [allocatable, setAllocatable] = useState(zone?.allocatable ?? true);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const { errors, formError, saving, submit } = useSubmit();
  const allocation = fn === 'ALLOCATION';

  const send = (event: FormEvent) => {
    event.preventDefault();
    setDeleteError(null);
    const found: Record<string, string> = {};
    if (!code.trim()) found.code = 'Enter a code';
    else if (!CODE_PATTERN.test(code.trim())) found.code = "Use letters, numbers, '-', '_' or '.'";
    if (!name.trim()) found.name = 'Enter a name';
    const used = allocation && allocatable;
    if (used && priority.trim() && !(/^[0-9]+$/.test(priority.trim()) && Number(priority) >= 1 && Number(priority) <= 999)) found.priority = 'A whole number from 1 to 999';
    void submit(found, async () => {
      const input = {
        function: fn,
        code: code.trim(),
        name: name.trim(),
        status,
        priority: used && priority.trim() ? Number(priority) : null,
        allocatable: !allocation || allocatable,
        version: zone?.version,
      };
      onSaved(zone ? await updateZone(zone.id, input) : await createZone(input));
    });
  };

  const remove = async () => {
    if (!zone) return;
    setDeleteError(null);
    try {
      await deleteZone(zone.id);
      onDeleted?.();
    } catch (error) {
      setDeleteError(errorMessage(error));
      setConfirmingDelete(false);
    }
  };

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {zone?.code}? Its locations stay.</span>
      <div className="button-row">
        <button type="button" className="button" onClick={() => setConfirmingDelete(false)}>
          Keep it
        </button>
        <button type="button" className="button button--danger" onClick={remove}>
          Delete
        </button>
      </div>
    </div>
  ) : (
    <>
      {zone && (
        <button type="button" className="button button--danger-quiet" onClick={() => setConfirmingDelete(true)} disabled={saving}>
          Delete
        </button>
      )}
      <div className="button-row button-row--end">
        <button type="button" className="button" onClick={onClose} disabled={saving}>
          Cancel
        </button>
        <button type="submit" form="zone-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : zone ? 'Save changes' : 'Create zone'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={zone ? `Edit ${zone.code}` : 'New zone'} onClose={onClose} footer={footer} permission="MASTER_DATA">
      <form id="zone-form" className="form" onSubmit={send} noValidate>
        {(formError ?? deleteError) && (
          <div className="form__error" role="alert">
            {formError ?? deleteError}
          </div>
        )}
        {zone ? (
          <p className="muted">
            {zoneFunctionLabel(fn)} zone. {ZONE_FUNCTIONS.find((f) => f.value === fn)?.hint}.
          </p>
        ) : (
          <Field id="zone-function" label="For" error={errors.function} hint={ZONE_FUNCTIONS.find((f) => f.value === fn)?.hint}>
            <select id="zone-function" value={fn} onChange={(e) => setFn(e.target.value as ZoneFunction)}>
              {ZONE_FUNCTIONS.map((f) => (
                <option key={f.value} value={f.value}>
                  {f.label}
                </option>
              ))}
            </select>
          </Field>
        )}
        <div className="grid grid--2">
          <Field id="zone-code" label="Code" error={errors.code}>
            <input
              id="zone-code"
              className="input--code"
              value={code}
              maxLength={40}
              autoComplete="off"
              aria-invalid={Boolean(errors.code)}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
            />
          </Field>
          <Field id="zone-status" label="Status" hint={status === 'INACTIVE' ? 'As if it were not there' : undefined}>
            <select id="zone-status" value={status} onChange={(e) => setStatus(e.target.value as ActiveStatus)}>
              {ACTIVE_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <Field id="zone-name" label="Name" error={errors.name}>
          <input id="zone-name" value={name} maxLength={120} autoComplete="off" aria-invalid={Boolean(errors.name)} onChange={(e) => setName(e.target.value)} />
        </Field>
        {allocation && (
          <>
            <label className="check">
              <input type="checkbox" checked={allocatable} onChange={(e) => setAllocatable(e.target.checked)} />
              <span>
                <span className="check__label">Orders get stock from here</span>
                <span className="check__hint">Off keeps the stock back, such as bulk storage or a quarantine area</span>
              </span>
            </label>
            {allocatable && (
              <Field id="zone-priority" label="Priority" error={errors.priority} hint="Lower is used first. Blank is last. Stock that expires sooner still goes first.">
                <input
                  id="zone-priority"
                  type="number"
                  inputMode="numeric"
                  min="1"
                  max="999"
                  step="1"
                  value={priority}
                  aria-invalid={Boolean(errors.priority)}
                  onChange={(e) => setPriority(e.target.value)}
                />
              </Field>
            )}
          </>
        )}
      </form>
    </Drawer>
  );
}
