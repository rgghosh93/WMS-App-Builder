import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import { currentUser, signOut, type DeviceUser } from '../../api/rf';
import { SignInScreen } from './SignInScreen';

type Session = { status: 'loading' } | { status: 'error'; message: string } | { status: 'out' } | { status: 'in'; user: DeviceUser };

export interface SignedIn {
  user: DeviceUser;
  /** Signs out and goes back to the sign-in screen. */
  leave: () => void;
  /** The server no longer knows this session: go back to sign-in. */
  onSignedOut: () => void;
  signOutError: string | null;
}

/** Shows its content to a signed-in user, and the sign-in screen to everyone else. */
export function SessionGate({ children }: { children: (session: SignedIn) => ReactNode }) {
  const [session, setSession] = useState<Session>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [signOutError, setSignOutError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setSession({ status: 'loading' });
    currentUser(controller.signal)
      .then((user) => setSession({ status: 'in', user }))
      .catch((error) => {
        if (controller.signal.aborted) return;
        if (error instanceof ApiError && error.status === 401) setSession({ status: 'out' });
        else setSession({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [attempt]);

  const onSignedOut = useCallback(() => setSession({ status: 'out' }), []);

  const leave = async () => {
    setSignOutError(null);
    try {
      await signOut();
      onSignedOut();
    } catch (error) {
      setSignOutError(errorMessage(error));
    }
  };

  if (session.status === 'loading') return <div className="rf rf--center muted">Loading…</div>;
  if (session.status === 'error') {
    return (
      <div className="rf rf--center">
        <p className="rf-error" role="alert">
          {session.message}
        </p>
        <button type="button" className="rf-button" onClick={() => setAttempt((n) => n + 1)}>
          Try again
        </button>
      </div>
    );
  }
  if (session.status === 'out') return <SignInScreen onSignedIn={(user) => setSession({ status: 'in', user })} />;
  return <>{children({ user: session.user, leave: () => void leave(), onSignedOut, signOutError })}</>;
}
