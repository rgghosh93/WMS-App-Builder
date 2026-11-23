import { useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import type { Permission } from '../../api/session';
import { createRole, deleteRole, updateRole, type PermissionInfo, type Role } from '../../api/users';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';

interface RoleFormProps {
  /** The role to edit, or null to create a new one. */
  role: Role | null;
  permissions: PermissionInfo[];
  onClose: () => void;
  onSaved: (role: Role, created: boolean) => void;
  onDeleted: (role: Role) => void;
}

type Errors = Partial<Record<string, string>>;

export function RoleForm({ role, permissions, onClose, onSaved, onDeleted }: RoleFormProps) {
  const fixed = role?.administrator ?? false;
  const [name, setName] = useState(role?.name ?? '');
  const [description, setDescription] = useState(role?.description ?? '');
  const [held, setHeld] = useState<Permission[]>(role?.permissions ?? []);
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const isDesktop = (id: Permission) => permissions.find((p) => p.id === id)?.group === 'DESKTOP';

  /** The other desktop permissions are no use without the desktop itself, so they come and go with it. */
  const toggle = (id: Permission, on: boolean) =>
    setHeld((current) => {
      if (!on) return current.filter((p) => p !== id && !(id === 'DESKTOP' && isDesktop(p)));
      const added: Permission[] = id !== 'DESKTOP' && isDesktop(id) && !current.includes('DESKTOP') ? ['DESKTOP', id] : [id];
      return [...current, ...added];
    });

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    setErrors(name.trim() ? {} : { name: 'Enter a name' });
    if (!name.trim()) return;
    setSaving(true);
    try {
      const input = { name: name.trim(), description: description.trim() || null, permissions: held, version: role?.version };
      onSaved(role ? await updateRole(role.id, input) : await createRole(input), role === null);
    } catch (error) {
      if (error instanceof ApiError) setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
      setFormError(errorMessage(error));
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!role) return;
    setSaving(true);
    setFormError(null);
    try {
      await deleteRole(role.id);
      onDeleted(role);
    } catch (error) {
      setFormError(errorMessage(error));
      setConfirmingDelete(false);
    } finally {
      setSaving(false);
    }
  };

  const footer = fixed ? (
    <div className="button-row button-row--end">
      <button type="button" className="button" onClick={onClose}>
        Close
      </button>
    </div>
  ) : confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {role?.name}? This can't be undone.</span>
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
      {role && (
        <button type="button" className="button button--danger-quiet" onClick={() => setConfirmingDelete(true)} disabled={saving}>
          Delete
        </button>
      )}
      <div className="button-row button-row--end">
        <button type="button" className="button" onClick={onClose} disabled={saving}>
          Cancel
        </button>
        <button type="submit" form="role-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : role ? 'Save changes' : 'Create role'}
        </button>
      </div>
    </>
  );

  const group = (id: PermissionInfo['group'], legend: string) => (
    <fieldset className="fieldset fieldset--stack" disabled={fixed}>
      <legend>{legend}</legend>
      {permissions
        .filter((p) => p.group === id)
        .map((p) => (
          <label key={p.id} className="check">
            <input type="checkbox" checked={held.includes(p.id)} onChange={(e) => toggle(p.id, e.target.checked)} />
            <span>
              <span className="check__label">{p.label}</span>
              {p.description && <span className="check__hint">{p.description}</span>}
            </span>
          </label>
        ))}
    </fieldset>
  );

  return (
    <Drawer title={role ? `${fixed ? '' : 'Edit '}${role.name}` : 'New role'} onClose={onClose} footer={footer}>
      <form id="role-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        {fixed && <p className="notice-info">This role always has every permission, so it can't be changed or deleted.</p>}
        {role && !fixed && role.userCount > 0 && (
          <p className="notice-info">
            {role.userCount} user{role.userCount === 1 ? ' has' : 's have'} this role. Changes count from their next click.
          </p>
        )}
        <Field id="role-name" label="Name" error={errors.name}>
          <input id="role-name" value={name} maxLength={80} disabled={fixed} aria-invalid={Boolean(errors.name)} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field id="role-description" label="Description" error={errors.description}>
          <input id="role-description" value={description} maxLength={250} disabled={fixed} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        {errors.permissions && <p className="field__error">{errors.permissions}</p>}
        {group('DESKTOP', 'On the desktop')}
        {group('DEVICE', 'On devices')}
      </form>
    </Drawer>
  );
}
