import { useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../api/http';
import { MIN_PASSWORD, changePassword, type DesktopUser } from '../api/session';
import { Field, sentenceCase } from '../components/Field';

/** Someone's own new password. The button to send it is wherever the form is shown. */
export function PasswordForm({ id, onChanged }: { id: string; onChanged: (user: DesktopUser) => void }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [errors, setErrors] = useState<Partial<Record<string, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    const found: Partial<Record<string, string>> = {};
    if (!current) found.currentPassword = 'Enter your password';
    if (next.length < MIN_PASSWORD) found.newPassword = `Use at least ${MIN_PASSWORD} characters`;
    else if (next !== again) found.again = "That isn't the same";
    setErrors(found);
    setFormError(null);
    if (Object.keys(found).length > 0) return;
    setBusy(true);
    try {
      onChanged(await changePassword(current, next));
    } catch (error) {
      if (error instanceof ApiError) setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
      setFormError(errorMessage(error));
      setBusy(false);
    }
  };

  const field = (key: string, label: string, value: string, set: (v: string) => void, autoComplete: string, hint?: string) => (
    <Field id={`${id}-${key}`} label={label} error={errors[key]} hint={hint}>
      <input
        id={`${id}-${key}`}
        type="password"
        value={value}
        maxLength={128}
        autoComplete={autoComplete}
        aria-invalid={Boolean(errors[key])}
        onChange={(e) => set(e.target.value)}
      />
    </Field>
  );

  return (
    <form id={id} className="form" onSubmit={submit} noValidate>
      {formError && (
        <div className="form__error" role="alert">
          {formError}
        </div>
      )}
      {field('currentPassword', 'Current password', current, setCurrent, 'current-password')}
      {field('newPassword', 'New password', next, setNext, 'new-password', `At least ${MIN_PASSWORD} characters. A few words you can remember work well.`)}
      {field('again', 'New password again', again, setAgain, 'new-password')}
      {busy && (
        <p className="muted" role="status">
          Saving…
        </p>
      )}
    </form>
  );
}
