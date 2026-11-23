import { useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import { Field } from '../../components/Field';

interface SecretFieldProps {
  id: string;
  label: string;
  hint: string;
  value: string;
  error?: string;
  numeric?: boolean;
  maxLength: number;
  /** What the button that makes one up says, and what it does. */
  makeLabel: string;
  make: () => string;
  onChange: (value: string) => void;
}

/** A PIN or password to give someone: typed hidden unless shown, or made up, in which case it is shown so it can be passed on. */
export function SecretField({ id, label, hint, value, error, numeric, maxLength, makeLabel, make, onChange }: SecretFieldProps) {
  const [shown, setShown] = useState(false);
  return (
    <Field id={id} label={label} error={error} hint={hint}>
      <div className="secret-field">
        <input
          id={id}
          className="input--code"
          type={shown ? 'text' : 'password'}
          inputMode={numeric ? 'numeric' : undefined}
          autoComplete="new-password"
          spellCheck={false}
          maxLength={maxLength}
          value={value}
          aria-invalid={Boolean(error)}
          onChange={(e) => onChange(e.target.value)}
        />
        <button
          type="button"
          className="icon-button"
          aria-label={`${shown ? 'Hide' : 'Show'} ${label}`}
          onClick={() => setShown((s) => !s)}
        >
          {shown ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />}
        </button>
        <button
          type="button"
          className="button"
          onClick={() => {
            onChange(make());
            setShown(true);
          }}
        >
          {makeLabel}
        </button>
      </div>
    </Field>
  );
}
