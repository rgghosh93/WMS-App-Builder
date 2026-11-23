import { useCallback, useEffect, useState } from 'react';
import { Outlet, useNavigate } from 'react-router-dom';
import { SESSION_STALE, errorMessage } from '../api/http';
import { getSession, goToFacility, signOutOfDesktop, type DesktopUser, type SessionView } from '../api/session';
import { Drawer } from '../components/Drawer';
import { NavigationProvider } from '../navigation/NavigationContext';
import { PasswordForm } from './PasswordForm';
import { SessionContext } from './SessionContext';
import { DesktopSignIn, SetupNotHere, SetupScreen, SignInFrame } from './SignInScreens';

type State = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; view: SessionView };

/** The desktop, for someone signed in; otherwise what they need to do first. */
export function DesktopGate() {
  const [state, setState] = useState<State>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const [changing, setChanging] = useState(false);
  const [signingIn, setSigningIn] = useState(false);

  // While looking again, what is there stays on screen.
  useEffect(() => {
    const controller = new AbortController();
    getSession(controller.signal)
      .then((view) => setState({ status: 'ready', view }))
      .catch((error) => {
        if (!controller.signal.aborted) setState((s) => (s.status === 'ready' ? s : { status: 'error', message: errorMessage(error) }));
      });
    return () => controller.abort();
  }, [attempt]);

  useEffect(() => {
    const look = () => setAttempt((n) => n + 1);
    // Another tab may have gone to another facility meanwhile, and what is done here would happen there.
    const back = () => document.visibilityState === 'visible' && look();
    window.addEventListener(SESSION_STALE, look);
    document.addEventListener('visibilitychange', back);
    return () => {
      window.removeEventListener(SESSION_STALE, look);
      document.removeEventListener('visibilitychange', back);
    };
  }, []);

  const signedIn = useCallback((user: DesktopUser) => {
    setSigningIn(false);
    setState({ status: 'ready', view: { state: 'SIGNED_IN', user, setupHere: false } });
  }, []);

  const navigate = useNavigate();
  const goTo = useCallback(
    async (facilityId: string) => {
      try {
        signedIn(await goToFacility(facilityId));
        // What was looked at is in the facility left behind.
        navigate('/');
        return null;
      } catch (error) {
        return errorMessage(error);
      }
    },
    [navigate, signedIn],
  );

  const signOut = useCallback(async () => {
    setChanging(false);
    try {
      await signOutOfDesktop();
    } finally {
      setAttempt((n) => n + 1);
    }
  }, []);

  if (state.status === 'loading') return <SignInFrame>Loading…</SignInFrame>;
  if (state.status === 'error') {
    return (
      <SignInFrame>
        <p className="form__error" role="alert">
          {state.message}
        </p>
        <button type="button" className="button" onClick={() => setAttempt((n) => n + 1)}>
          Try again
        </button>
      </SignInFrame>
    );
  }

  const { view } = state;
  if (view.state === 'SETUP' && !signingIn) {
    const skip = () => setSigningIn(true);
    if (view.setupHere || view.setupWithKey) return <SetupScreen onSignedIn={signedIn} onSkip={skip} needsKey={!view.setupHere} />;
    return <SetupNotHere onSkip={skip} />;
  }
  if (!view.user) return <DesktopSignIn onSignedIn={signedIn} />;
  if (view.user.mustChangePassword) {
    return (
      <SignInFrame title="Choose your password">
        <p className="muted">Your password was set by someone else. Choose one only you know.</p>
        <PasswordForm id="own-password" onChanged={signedIn} />
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={() => void signOut()}>
            Sign out
          </button>
          <button type="submit" form="own-password" className="button button--primary">
            Save password
          </button>
        </div>
      </SignInFrame>
    );
  }

  return (
    <SessionContext.Provider value={{ user: view.user, signOut: () => void signOut(), goTo, changePassword: () => setChanging(true) }}>
      {/* Who it is decides which pages there are, and where they are what is on them. */}
      <NavigationProvider key={`${view.user.id}-${view.user.facility?.id}-${view.user.permissions.join()}`}>
        <Outlet />
      </NavigationProvider>
      {changing && (
        <Drawer
          title="Change password"
          onClose={() => setChanging(false)}
          footer={
            <div className="button-row button-row--end">
              <button type="button" className="button" onClick={() => setChanging(false)}>
                Cancel
              </button>
              <button type="submit" form="change-password" className="button button--primary">
                Save password
              </button>
            </div>
          }
        >
          <p className="muted">You stay signed in here, and are signed out everywhere else.</p>
          <PasswordForm
            id="change-password"
            onChanged={(user) => {
              setChanging(false);
              signedIn(user);
            }}
          />
        </Drawer>
      )}
    </SessionContext.Provider>
  );
}
