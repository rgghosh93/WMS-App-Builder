import type { ReactNode } from 'react';

interface FieldProps {
  id: string;
  label: string;
  error?: string;
  hint?: string;
  children: ReactNode;
}

/** A labelled form control with an optional hint and error message. */
export function Field({ id, label, error, hint, children }: FieldProps) {
  return (
    <div className={`field${error ? ' field--invalid' : ''}`}>
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && !error && <p className="field__hint">{hint}</p>}
      {error && <p className="field__error">{error}</p>}
    </div>
  );
}

export function sentenceCase(message: string): string {
  return message.charAt(0).toUpperCase() + message.slice(1);
}
