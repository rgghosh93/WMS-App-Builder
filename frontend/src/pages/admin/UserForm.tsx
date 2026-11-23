import { useEffect, useState, type FormEvent } from 'react';
import { ACTIVE_STATUSES, type ActiveStatus } from '../../api/containerTypes';
import { listFacilities, type Facility } from '../../api/facilities';
import { ApiError, errorMessage } from '../../api/http';
import {
  createUser,
  deleteUser,
  getUser,
  listRoles,
  pinError,
  randomPin,
  setUserPassword,
  setUserPin,
  signOutUser,
  unlockUser,
  updateUser,
  type Role,
  type User,
} from '../../api/users';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';
import { formatDateTime } from '../outbound/OrdersPage';
import { MIN_PASSWORD, randomPassword } from '../../api/session';
import { SecretField } from './SecretField';

interface UserFormProps {
  /** The user to edit, or null to create a new one. */
  id: string | null;
  onClose: () => void;
  onSaved: (user: User, created: boolean) => void;
  onDeleted: (user: User) => void;
}

type Errors = Partial<Record<string, string>>;

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

/** A user. Name, status and roles are saved together; a new PIN or password, unlocking and signing out happen at once. */
export function UserForm({ id, onClose, onSaved, onDeleted }: UserFormProps) {
  const [user, setUser] = useState<User | null>(null);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [status, setStatus] = useState<ActiveStatus>('ACTIVE');
  const [roles, setRoles] = useState<Role[]>([]);
  const [roleIds, setRoleIds] = useState<string[]>([]);
  const [facilities, setFacilities] = useState<Facility[]>([]);
  const [facilityIds, setFacilityIds] = useState<string[]>([]);
  const [pin, setPin] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listRoles(controller.signal)
      .then(setRoles)
      .catch((error) => {
        if (!controller.signal.aborted) setFormError(errorMessage(error));
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    listFacilities(controller.signal).then(
      (found) => Array.isArray(found) && setFacilities(found),
      () => undefined,
    );
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!id) return;
    const controller = new AbortController();
    getUser(id, controller.signal)
      .then((loaded) => {
        setUser(loaded);
        setCode(loaded.userCode);
        setName(loaded.displayName);
        setStatus(loaded.status);
        setRoleIds(loaded.roles.map((r) => r.id));
        setFacilityIds((loaded.facilities ?? []).map((f) => f.id));
      })
      .catch((error) => {
        if (!controller.signal.aborted) setFormError(errorMessage(error));
      });
    return () => controller.abort();
  }, [id]);

  /** Runs one action, showing what went wrong on the fields it concerns. */
  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setFormError(null);
    setErrors({});
    setDone(null);
    try {
      await action();
    } catch (error) {
      if (error instanceof ApiError) setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
      setFormError(errorMessage(error));
    } finally {
      setBusy(false);
    }
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found: Errors = {};
    if (!code.trim()) found.userCode = 'Enter a user ID';
    else if (!CODE_PATTERN.test(code.trim())) found.userCode = "Use letters, numbers, '-', '_' or '.'";
    if (!name.trim()) found.displayName = 'Enter a name';
    if (!id) {
      if (!pin && !password) found.pin = 'Give a PIN, a password or both';
      else if (pin) {
        const weak = pinError(pin);
        if (weak) found.pin = weak;
      }
      if (password && password.length < MIN_PASSWORD) found.password = `Use at least ${MIN_PASSWORD} characters`;
    }
    setErrors(found);
    setFormError(null);
    if (Object.keys(found).length > 0) return;
    const input = { userCode: code.trim(), displayName: name.trim(), status, roleIds, facilityIds };
    await run(async () => {
      if (id) onSaved(await updateUser(id, { ...input, version: user!.version }), false);
      else onSaved(await createUser({ ...input, pin: pin || null, password: password || null }), true);
    });
  };

  const changePin = async () => {
    const weak = pinError(pin);
    if (weak) {
      setErrors({ pin: weak });
      return;
    }
    await run(async () => {
      setUser(await setUserPin(id!, pin));
      setPin('');
      setDone('The new PIN works now. They were signed out everywhere.');
    });
  };

  const changePassword = async () => {
    if (password.length < MIN_PASSWORD) {
      setErrors({ password: `Use at least ${MIN_PASSWORD} characters` });
      return;
    }
    await run(async () => {
      setUser(await setUserPassword(id!, password));
      setPassword('');
      setDone('The new password works now, to choose their own with. They were signed out everywhere.');
    });
  };

  const pinField = (
    <SecretField
      id="user-pin"
      label="PIN"
      hint="For devices. 4 to 12 digits; it can't be looked up later."
      numeric
      maxLength={12}
      makeLabel="Make a PIN"
      make={randomPin}
      value={pin}
      error={errors.pin}
      onChange={setPin}
    />
  );
  const passwordField = (
    <SecretField
      id="user-password"
      label="Password"
      hint={`For the desktop. At least ${MIN_PASSWORD} characters; they choose their own the first time.`}
      maxLength={128}
      makeLabel="Make a password"
      make={randomPassword}
      value={password}
      error={errors.password}
      onChange={setPassword}
    />
  );

  const remove = () =>
    run(async () => {
      try {
        await deleteUser(id!);
        onDeleted(user!);
      } finally {
        setConfirmingDelete(false);
      }
    });

  const title = id ? `Edit ${user?.userCode ?? 'user'}` : 'New user';
  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {user?.userCode}? This can't be undone.</span>
      <div className="button-row">
        <button type="button" className="button" onClick={() => setConfirmingDelete(false)} disabled={busy}>
          Keep them
        </button>
        <button type="button" className="button button--danger" onClick={remove} disabled={busy}>
          Delete
        </button>
      </div>
    </div>
  ) : (
    <>
      {user && (
        <button type="button" className="button button--danger-quiet" onClick={() => setConfirmingDelete(true)} disabled={busy}>
          Delete
        </button>
      )}
      <div className="button-row button-row--end">
        <button type="button" className="button" onClick={onClose} disabled={busy}>
          {id ? 'Close' : 'Cancel'}
        </button>
        <button type="submit" form="user-form" className="button button--primary" disabled={busy || (id !== null && !user)}>
          {id ? 'Save changes' : busy ? 'Creating…' : 'Create user'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={title} onClose={onClose} footer={footer}>
      <form id="user-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        {done && (
          <p className="notice-info" role="status">
            {done}
          </p>
        )}
        {id && !user && !formError && <p className="muted">Loading…</p>}
        {(!id || user) && (
          <>
            <div className="grid grid--2">
              <Field id="user-code" label="User ID" error={errors.userCode} hint="What they sign in with">
                <input
                  id="user-code"
                  className="input--code"
                  value={code}
                  maxLength={40}
                  autoComplete="off"
                  aria-invalid={Boolean(errors.userCode)}
                  onChange={(e) => setCode(e.target.value.toUpperCase())}
                />
              </Field>
              <Field id="user-status" label="Status" hint={id ? 'Inactive users are signed out' : undefined}>
                <select id="user-status" value={status} onChange={(e) => setStatus(e.target.value as ActiveStatus)}>
                  {ACTIVE_STATUSES.map((s) => (
                    <option key={s.value} value={s.value}>
                      {s.label}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <Field id="user-name" label="Name" error={errors.displayName} hint="Shown on tasks and activity">
              <input
                id="user-name"
                value={name}
                maxLength={120}
                autoComplete="off"
                aria-invalid={Boolean(errors.displayName)}
                onChange={(e) => setName(e.target.value)}
              />
            </Field>
            {user && user.inHand.length > 0 && (
              <p className="notice-info">
                In the middle of: {user.inHand.join(', ')}.{status === 'INACTIVE' && ' Someone else can take it over by scanning the container.'}
              </p>
            )}
            <fieldset className="fieldset fieldset--stack">
              <legend>Roles</legend>
              {errors.roleIds && <p className="field__error">{errors.roleIds}</p>}
              {roles.map((r) => (
                <label key={r.id} className="check">
                  <input
                    type="checkbox"
                    checked={roleIds.includes(r.id)}
                    onChange={(e) => setRoleIds((ids) => (e.target.checked ? [...ids, r.id] : ids.filter((x) => x !== r.id)))}
                  />
                  <span>
                    <span className="check__label">{r.name}</span>
                    {r.description && <span className="check__hint">{r.description}</span>}
                  </span>
                </label>
              ))}
            </fieldset>
            {facilities.length > 1 && (
              <fieldset className="fieldset fieldset--stack">
                <legend>Works in</legend>
                <p className="field__hint">{facilityIds.length === 0 ? 'Every facility, as none is chosen.' : 'Only the facilities chosen.'}</p>
                {errors.facilityIds && <p className="field__error">{errors.facilityIds}</p>}
                {facilities
                  .filter((f) => f.status === 'ACTIVE' || facilityIds.includes(f.id))
                  .map((f) => (
                    <label key={f.id} className="check">
                      <input
                        type="checkbox"
                        checked={facilityIds.includes(f.id)}
                        onChange={(e) => setFacilityIds((ids) => (e.target.checked ? [...ids, f.id] : ids.filter((x) => x !== f.id)))}
                      />
                      <span className="check__label">
                        {f.code} · {f.name}
                      </span>
                    </label>
                  ))}
              </fieldset>
            )}
            {!id && (
              <fieldset className="fieldset fieldset--stack">
                <legend>Signing in</legend>
                <p className="field__hint">Give a PIN, a password or both, depending on where they work.</p>
                {pinField}
                {passwordField}
              </fieldset>
            )}
          </>
        )}
        {user && (
          <>
            <fieldset className="fieldset fieldset--stack">
              <legend>Signing in</legend>
              <p className="field__hint">
                {user.lastSignInAt ? `Last signed in ${formatDateTime(user.lastSignInAt)}.` : 'Never signed in.'}{' '}
                {user.devices > 0 ? `Signed in on ${user.devices} device${user.devices === 1 ? '' : 's'} or browser${user.devices === 1 ? '' : 's'}.` : 'Not signed in anywhere.'}
              </p>
              {user.lockedUntil && <p className="field__error">Locked out after wrong PINs or passwords until {formatDateTime(user.lockedUntil)}.</p>}
              {(user.lockedUntil || user.devices > 0) && (
                <div className="button-row">
                  {user.lockedUntil && (
                    <button
                      type="button"
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          setUser(await unlockUser(user.id));
                          setDone(`${user.userCode} can sign in again.`);
                        })
                      }
                    >
                      Unlock
                    </button>
                  )}
                  {user.devices > 0 && (
                    <button
                      type="button"
                      className="button"
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          setUser(await signOutUser(user.id));
                          setDone(`${user.userCode} is signed out everywhere.`);
                        })
                      }
                    >
                      Sign out everywhere
                    </button>
                  )}
                </div>
              )}
            </fieldset>
            <fieldset className="fieldset fieldset--stack">
              <legend>{user.hasPin ? 'New PIN' : 'Give a PIN'}</legend>
              <p className="field__hint">
                {user.hasPin ? 'Replaces the old PIN at once and signs them out everywhere.' : 'They have no PIN, so they can’t use devices.'}
              </p>
              {pinField}
              <div className="button-row">
                <button type="button" className="button" disabled={busy || !pin} onClick={changePin}>
                  Set new PIN
                </button>
              </div>
            </fieldset>
            <fieldset className="fieldset fieldset--stack">
              <legend>{user.hasPassword ? 'New password' : 'Give a password'}</legend>
              <p className="field__hint">
                {user.hasPassword
                  ? 'Replaces the old password at once and signs them out everywhere.'
                  : 'They have no password, so they can’t use the desktop.'}
              </p>
              {passwordField}
              <div className="button-row">
                <button type="button" className="button" disabled={busy || !password} onClick={changePassword}>
                  Set new password
                </button>
              </div>
            </fieldset>
          </>
        )}
      </form>
    </Drawer>
  );
}
