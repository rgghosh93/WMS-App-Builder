import { useState, type FormEvent } from 'react';
import { Warehouse } from 'lucide-react';
import { errorMessage } from '../../api/http';
import { signIn, type DeviceUser } from '../../api/rf';

export function SignInScreen({ onSignedIn }: { onSignedIn: (user: DeviceUser) => void }) {
  const [userCode, setUserCode] = useState('');
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!userCode.trim() || !pin) {
      setError('Enter your user ID and PIN');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onSignedIn(await signIn(userCode.trim(), pin));
    } catch (e) {
      setError(errorMessage(e));
      setPin('');
      setBusy(false);
    }
  };

  return (
    <div className="rf rf--center">
      <form className="rf-signin" onSubmit={submit} noValidate>
        <h1 className="rf-signin__title">
          <Warehouse size={28} aria-hidden="true" strokeWidth={1.75} />
          Sign in
        </h1>
        <label className="scan__label" htmlFor="rf-user">
          User ID
        </label>
        <input
          id="rf-user"
          className="scan__input"
          value={userCode}
          autoFocus
          autoComplete="username"
          autoCapitalize="characters"
          spellCheck={false}
          onChange={(e) => setUserCode(e.target.value)}
        />
        <label className="scan__label" htmlFor="rf-pin">
          PIN
        </label>
        <input
          id="rf-pin"
          className="scan__input"
          type="password"
          inputMode="numeric"
          value={pin}
          autoComplete="current-password"
          onChange={(e) => setPin(e.target.value)}
        />
        {error && (
          <p className="rf-error" role="alert">
            {error}
          </p>
        )}
        <button type="submit" className="rf-button rf-button--primary" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
