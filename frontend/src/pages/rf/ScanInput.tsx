import { useEffect, useId, useRef, useState, type FormEvent, type HTMLAttributes } from 'react';
import { CornerDownLeft } from 'lucide-react';

interface ScanInputProps {
  label: string;
  onScan: (value: string) => void;
  busy?: boolean;
  /** What went wrong with the last scan. A new message clears the box so the next scan starts fresh. */
  error?: string | null;
  inputMode?: HTMLAttributes<HTMLInputElement>['inputMode'];
}

/**
 * A box for a barcode scanner, which types the code and presses Enter. It takes focus as soon
 * as it appears, and typing works too.
 */
export function ScanInput({ label, onScan, busy = false, error, inputMode }: ScanInputProps) {
  const [value, setValue] = useState('');
  const ref = useRef<HTMLInputElement>(null);
  const id = useId();

  useEffect(() => {
    if (!busy) ref.current?.focus();
  }, [busy, error]);

  useEffect(() => {
    if (!error) return;
    setValue('');
    navigator.vibrate?.(200);
  }, [error]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const scanned = value.trim();
    if (!scanned || busy) return;
    setValue('');
    onScan(scanned);
  };

  return (
    <form className="scan" onSubmit={submit}>
      <label className="scan__label" htmlFor={id}>
        {label}
      </label>
      <div className="scan__row">
        <input
          id={id}
          ref={ref}
          className="scan__input"
          value={value}
          autoComplete="off"
          autoCapitalize="characters"
          autoCorrect="off"
          spellCheck={false}
          inputMode={inputMode}
          aria-invalid={Boolean(error)}
          disabled={busy}
          onChange={(e) => setValue(e.target.value)}
        />
        <button type="submit" className="scan__go" aria-label="Enter" disabled={busy || !value.trim()}>
          <CornerDownLeft size={22} aria-hidden="true" />
        </button>
      </div>
      {error && (
        <p className="rf-error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
