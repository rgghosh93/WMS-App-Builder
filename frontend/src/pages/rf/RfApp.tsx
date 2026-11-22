import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate, Route, Routes } from 'react-router-dom';
import { ChevronRight, Hand, LogOut, Menu as MenuIcon } from 'lucide-react';
import { ApiError, errorMessage } from '../../api/http';
import { currentUser, signOut, type DeviceUser } from '../../api/rf';
import { PickingTransaction } from './PickingTransaction';
import { SignInScreen } from './SignInScreen';

type Session = { status: 'loading' } | { status: 'error'; message: string } | { status: 'out' } | { status: 'in'; user: DeviceUser };

/** The mobile device app: sign in, choose a transaction, work through it. */
export function RfApp() {
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

  return (
    <div className="rf">
      <header className="rf-bar">
        <Link to="/rf" className="rf-bar__button" aria-label="Menu">
          <MenuIcon size={22} aria-hidden="true" />
        </Link>
        <span className="rf-bar__user">{session.user.displayName}</span>
        <button type="button" className="rf-bar__button" aria-label="Sign out" onClick={leave}>
          <LogOut size={20} aria-hidden="true" />
        </button>
      </header>
      <main className="rf-main">
        {signOutError && (
          <p className="rf-error" role="alert">
            {signOutError}
          </p>
        )}
        <Routes>
          <Route index element={<TransactionMenu />} />
          <Route path="picking" element={<PickingTransaction onSignedOut={onSignedOut} />} />
          <Route path="*" element={<Navigate to="/rf" replace />} />
        </Routes>
      </main>
    </div>
  );
}

function TransactionMenu() {
  return (
    <>
      <h1 className="rf-title">Transactions</h1>
      <nav aria-label="Transactions">
        <Link to="/rf/picking" className="rf-menu-item">
          <Hand size={24} aria-hidden="true" />
          <span>Picking</span>
          <ChevronRight size={20} aria-hidden="true" />
        </Link>
      </nav>
    </>
  );
}
