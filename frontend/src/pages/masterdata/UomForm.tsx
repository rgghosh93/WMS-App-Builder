import { useState, type FormEvent } from 'react';
import { ACTIVE_STATUSES, type ActiveStatus } from '../../api/containerTypes';
import { UOM_KINDS, createUom, deleteUom, updateUom, type Uom, type UomInput, type UomKind } from '../../api/uoms';
import { ApiError, errorMessage } from '../../api/http';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';

interface UomFormProps {
  /** The unit to edit, or null to create a new one. */
  uom: Uom | null;
  onClose: () => void;
  onSaved: (uom: Uom, created: boolean) => void;
  onDeleted: (uom: Uom) => void;
}

type Errors = Partial<Record<string, string>>;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export function UomForm({ uom, onClose, onSaved, onDeleted }: UomFormProps) {
  const isNew = uom === null;
  const isBase = uom?.base ?? false;
  const [code, setCode] = useState(uom?.uomCode ?? '');
  const [description, setDescription] = useState(uom?.description ?? '');
  const [kind, setKind] = useState<UomKind>(uom?.kind ?? 'CASE');
  const [status, setStatus] = useState<ActiveStatus>(uom?.status ?? 'ACTIVE');
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const validate = (): Errors => {
    const found: Errors = {};
    if (!code.trim()) found.uomCode = 'Enter a code';
    else if (!CODE_PATTERN.test(code.trim())) found.uomCode = "Use letters, numbers, '-', '_' or '.'";
    if (!description.trim()) found.description = 'Enter a description';
    return found;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    const input: UomInput = { uomCode: code.trim(), description: description.trim(), kind, status, version: uom?.version };
    setSaving(true);
    try {
      const saved = isNew ? await createUom(input) : await updateUom(uom.id, input);
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
    if (!uom) return;
    setSaving(true);
    setFormError(null);
    try {
      await deleteUom(uom.id);
      onDeleted(uom);
    } catch (error) {
      setFormError(errorMessage(error));
      setConfirmingDelete(false);
    } finally {
      setSaving(false);
    }
  };

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {uom?.uomCode}? This can't be undone.</span>
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
      {!isNew && !isBase && (
        <button type="button" className="button button--danger-quiet" onClick={() => setConfirmingDelete(true)} disabled={saving}>
          Delete
        </button>
      )}
      <div className="button-row button-row--end">
        <button type="button" className="button" onClick={onClose} disabled={saving}>
          Cancel
        </button>
        <button type="submit" form="uom-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : isNew ? 'Create unit' : 'Save changes'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={isNew ? 'New unit of measure' : `Edit ${uom.uomCode}`} onClose={onClose} footer={footer}>
      <form id="uom-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        {isBase && uom && (
          <p className="notice-info">
            {uom.uomCode} is the base unit: every quantity in the system is counted in it. Only its description can be changed.
          </p>
        )}

        <div className="grid grid--2">
          <Field id="uom-code" label="Code" error={errors.uomCode} hint="Short code shown on screens and labels, e.g. CS">
            <input
              id="uom-code"
              className="input--code"
              value={code}
              maxLength={10}
              autoComplete="off"
              disabled={isBase}
              aria-invalid={Boolean(errors.uomCode)}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
            />
          </Field>
          <Field id="uom-kind" label="Packaging level" error={errors.kind}>
            <select id="uom-kind" value={kind} disabled={isBase} onChange={(e) => setKind(e.target.value as UomKind)}>
              {isBase && <option value="EACH">Each</option>}
              {UOM_KINDS.map((k) => (
                <option key={k.value} value={k.value}>
                  {k.label}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <Field id="uom-description" label="Description" error={errors.description}>
          <input
            id="uom-description"
            value={description}
            maxLength={100}
            aria-invalid={Boolean(errors.description)}
            onChange={(e) => setDescription(e.target.value)}
          />
        </Field>

        <Field id="uom-status" label="Status" hint={isBase ? undefined : 'Inactive units stay on items that have them but can\'t be added to more.'}>
          <select id="uom-status" value={status} disabled={isBase} onChange={(e) => setStatus(e.target.value as ActiveStatus)}>
            {ACTIVE_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </Field>
      </form>
    </Drawer>
  );
}
