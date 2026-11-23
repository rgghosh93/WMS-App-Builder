import { useState, type FormEvent } from 'react';
import {
  CONTAINER_STATUSES,
  createContainer,
  deleteContainer,
  updateContainer,
  type Container,
  type ContainerInput,
  type ContainerStatus,
  type LocationRef,
} from '../../api/containers';
import { categoryLabel, type ContainerType } from '../../api/containerTypes';
import { ApiError, errorMessage } from '../../api/http';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';
import { LocationPicker } from '../../components/LocationPicker';

interface ContainerFormProps {
  /** The container to edit, or null to create a new one. */
  container: Container | null;
  /** Reusable container types that permanent containers can use. */
  types: ContainerType[];
  onClose: () => void;
  onSaved: (container: Container, created: boolean) => void;
  onDeleted: (container: Container) => void;
}

interface FormValues {
  lpnId: string;
  containerTypeId: string;
  status: ContainerStatus;
  location: LocationRef | null;
  notes: string;
}

type Errors = Partial<Record<string, string>>;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

export function ContainerForm({ container, types, onClose, onSaved, onDeleted }: ContainerFormProps) {
  const isNew = container === null;
  const [values, setValues] = useState<FormValues>(() => ({
    lpnId: container?.lpnId ?? '',
    containerTypeId: container?.containerType.id ?? '',
    status: container?.status ?? 'AVAILABLE',
    location: container?.currentLocation ?? null,
    notes: container?.notes ?? '',
  }));
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  // Active types can be chosen; an inactive type only shows if this container already has it.
  const typeOptions = types.filter((t) => t.status === 'ACTIVE' || t.id === container?.containerType.id);

  const set = <K extends keyof FormValues>(field: K, value: FormValues[K]) => {
    setValues((v) => ({ ...v, [field]: value }));
    const errorKey = field === 'location' ? 'currentLocationId' : field;
    setErrors((current) => {
      const { [errorKey]: _removed, ...rest } = current;
      return rest;
    });
  };

  const validate = (): Errors => {
    const found: Errors = {};
    const lpn = values.lpnId.trim();
    if (!lpn) found.lpnId = 'Enter an LPN';
    else if (!CODE_PATTERN.test(lpn)) found.lpnId = "Use letters, numbers, '-', '_' or '.'";
    if (!values.containerTypeId) found.containerTypeId = 'Choose a container type';
    return found;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;

    const input: ContainerInput = {
      lpnId: values.lpnId.trim(),
      containerTypeId: values.containerTypeId,
      status: values.status,
      currentLocationId: values.location?.id ?? null,
      notes: values.notes.trim() || null,
      version: container?.version,
    };

    setSaving(true);
    try {
      const saved = isNew ? await createContainer(input) : await updateContainer(container.id, input);
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
    if (!container) return;
    setSaving(true);
    setFormError(null);
    try {
      await deleteContainer(container.id);
      onDeleted(container);
    } catch (error) {
      setFormError(errorMessage(error));
      setConfirmingDelete(false);
    } finally {
      setSaving(false);
    }
  };

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {container?.lpnId}? This can't be undone. To keep its history, set it to Retired instead.</span>
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
        <button type="submit" form="container-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : isNew ? 'Create container' : 'Save changes'}
        </button>
      </div>
    </>
  );

  const statusHint = CONTAINER_STATUSES.find((s) => s.value === values.status)?.hint;

  return (
    <Drawer title={isNew ? 'New container' : `Edit ${container.lpnId}`} onClose={onClose} footer={footer} permission="MASTER_DATA">
      <form id="container-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}

        <Field id="container-lpn" label="LPN" error={errors.lpnId} hint="The license plate number printed on the container's label.">
          <input
            id="container-lpn"
            className="input--code"
            value={values.lpnId}
            maxLength={40}
            autoComplete="off"
            aria-invalid={Boolean(errors.lpnId)}
            onChange={(e) => set('lpnId', e.target.value.toUpperCase())}
          />
        </Field>

        <Field id="container-type" label="Container type" error={errors.containerTypeId} hint="Only reusable types are listed.">
          <select
            id="container-type"
            value={values.containerTypeId}
            aria-invalid={Boolean(errors.containerTypeId)}
            onChange={(e) => set('containerTypeId', e.target.value)}
          >
            <option value="">Choose a type…</option>
            {typeOptions.map((t) => (
              <option key={t.id} value={t.id}>
                {t.containerTypeId} · {t.description} ({categoryLabel(t.category)}){t.status === 'INACTIVE' ? ' · inactive' : ''}
              </option>
            ))}
          </select>
        </Field>

        <Field id="container-status" label="Status" hint={statusHint}>
          <select id="container-status" value={values.status} onChange={(e) => set('status', e.target.value as ContainerStatus)}>
            {CONTAINER_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </Field>

        <Field id="container-location" label="Current location" error={errors.currentLocationId} hint="Optional. Where the container is now.">
          <LocationPicker
            id="container-location"
            value={values.location}
            invalid={Boolean(errors.currentLocationId)}
            onChange={(location) => set('location', location)}
          />
        </Field>

        <Field id="container-notes" label="Notes" error={errors.notes}>
          <textarea
            id="container-notes"
            rows={3}
            maxLength={500}
            value={values.notes}
            onChange={(e) => set('notes', e.target.value)}
          />
        </Field>
      </form>
    </Drawer>
  );
}
