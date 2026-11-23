import { useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Warehouse } from 'lucide-react';
import { ApiError, errorMessage } from '../api/http';
import { MIN_PASSWORD, setUpAdministrator, signInToDesktop, type DesktopUser } from '../api/session';
import { Field, sentenceCase } from '../components/Field';

type Errors = Partial<Record<string, string>>;

/** A card in the middle of an otherwise empty page. */
export function SignInFrame({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <main className="signin">
      <section className="signin__card">
        <p className="signin__brand">
          <Warehouse size={26} aria-hidden="true" strokeWidth={1.75} />
          WMS
        </p>
        {title && <h1>{title}</h1>}
        {children}
      </section>
    </main>
  );
}

export function DesktopSignIn({ onSignedIn }: { onSignedIn: (user: DesktopUser) => void }) {
  const [userCode, setUserCode] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!userCode.trim() || !password) {
      setError('Enter your user ID and password');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      onSignedIn(await signInToDesktop(userCode.trim(), password));
    } catch (e) {
      setError(errorMessage(e));
      setPassword('');
      setBusy(false);
    }
  };

  return (
    <SignInFrame title="Sign in">
      <form className="form" onSubmit={submit} noValidate>
        {error && (
          <div className="form__error" role="alert">
            {error}
          </div>
        )}
        <Field id="signin-user" label="User ID">
          <input
            id="signin-user"
            className="input--code"
            value={userCode}
            maxLength={40}
            autoFocus
            autoComplete="username"
            spellCheck={false}
            onChange={(e) => setUserCode(e.target.value.toUpperCase())}
          />
        </Field>
        <Field id="signin-password" label="Password">
          <input
            id="signin-password"
            type="password"
            value={password}
            maxLength={200}
            autoComplete="current-password"
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <button type="submit" className="button button--primary signin__submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p className="muted signin__other">
        Working on a device? <Link to="/rf">Sign in with a PIN</Link>
      </p>
    </SignInFrame>
  );
}

/** The first administrator, made on the computer the WMS runs on. */
export function SetupScreen({ onSignedIn, onSkip }: { onSignedIn: (user: DesktopUser) => void; onSkip: () => void }) {
  const [userCode, setUserCode] = useState('');
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [again, setAgain] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const found: Errors = {};
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(userCode.trim())) found.userCode = "Use letters, numbers, '-', '_' or '.'";
    if (!name.trim()) found.displayName = 'Enter your name';
    if (password.length < MIN_PASSWORD) found.password = `Use at least ${MIN_PASSWORD} characters`;
    else if (password !== again) found.again = "That isn't the same";
    setErrors(found);
    setFormError(null);
    if (Object.keys(found).length > 0) return;
    setBusy(true);
    try {
      onSignedIn(await setUpAdministrator(userCode.trim(), name.trim(), password));
    } catch (error) {
      if (error instanceof ApiError) setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
      setFormError(errorMessage(error));
      setBusy(false);
    }
  };

  return (
    <SignInFrame title="Create the first administrator">
      <p className="muted">Nobody can sign in to the desktop yet. This person manages users and roles, and can do everything else.</p>
      <form className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <Field id="setup-user" label="User ID" error={errors.userCode} hint="What you sign in with">
          <input
            id="setup-user"
            className="input--code"
            value={userCode}
            maxLength={40}
            autoFocus
            autoComplete="username"
            spellCheck={false}
            aria-invalid={Boolean(errors.userCode)}
            onChange={(e) => setUserCode(e.target.value.toUpperCase())}
          />
        </Field>
        <Field id="setup-name" label="Name" error={errors.displayName}>
          <input id="setup-name" value={name} maxLength={120} autoComplete="name" aria-invalid={Boolean(errors.displayName)} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field id="setup-password" label="Password" error={errors.password} hint={`At least ${MIN_PASSWORD} characters. A few words you can remember work well.`}>
          <input
            id="setup-password"
            type="password"
            value={password}
            maxLength={128}
            autoComplete="new-password"
            aria-invalid={Boolean(errors.password)}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <Field id="setup-again" label="Password again" error={errors.again}>
          <input id="setup-again" type="password" value={again} maxLength={128} autoComplete="new-password" aria-invalid={Boolean(errors.again)} onChange={(e) => setAgain(e.target.value)} />
        </Field>
        <button type="submit" className="button button--primary signin__submit" disabled={busy}>
          {busy ? 'Creating…' : 'Create administrator'}
        </button>
      </form>
      <p className="muted signin__other">
        <button type="button" className="link-button" onClick={onSkip}>
          I already have a password
        </button>{' '}
        · <Link to="/rf">Sign in on a device</Link>
      </p>
    </SignInFrame>
  );
}

export function SetupNotHere({ onSkip }: { onSkip: () => void }) {
  return (
    <SignInFrame title="Not set up yet">
      <p>Nobody can sign in to the desktop yet. Open the WMS on the computer it runs on to create the first administrator.</p>
      <p className="muted signin__other">
        <button type="button" className="link-button" onClick={onSkip}>
          I already have a password
        </button>{' '}
        · <Link to="/rf">Sign in on a device</Link>
      </p>
    </SignInFrame>
  );
}
