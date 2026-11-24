import { useState } from 'react';
import { Link, Outlet } from 'react-router-dom';
import { Building2, KeyRound, LogOut, Menu, Warehouse } from 'lucide-react';
import { useSession } from '../session/SessionContext';
import { NotificationBell } from '../pages/builder/NotificationBell';
import { Sidebar } from './Sidebar';

export function AppShell() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const session = useSession();
  const [problem, setProblem] = useState<string | null>(null);
  const facility = session?.user.facility;
  const facilities = session?.user.facilities ?? [];

  return (
    <div className="shell">
      <header className="topbar">
        <button
          type="button"
          className="topbar__menu"
          aria-label={sidebarOpen ? 'Close menu' : 'Open menu'}
          aria-expanded={sidebarOpen}
          onClick={() => setSidebarOpen((open) => !open)}
        >
          <Menu size={20} aria-hidden="true" />
        </button>
        <Link to="/" className="topbar__brand">
          <Warehouse size={22} aria-hidden="true" strokeWidth={1.75} />
          <span>WMS</span>
        </Link>
        {session && facility && (
          <div className="topbar__facility" title={facility.name}>
            <Building2 size={16} aria-hidden="true" />
            {facilities.length > 1 && session.goTo ? (
              <select
                aria-label="Facility"
                value={facility.id}
                onChange={(e) => void session.goTo!(e.target.value).then(setProblem)}
              >
                {facilities.map((f) => (
                  <option key={f.id} value={f.id}>
                    {f.code} · {f.name}
                  </option>
                ))}
              </select>
            ) : (
              <span>{facility.name}</span>
            )}
          </div>
        )}
        {session && (
          <div className="topbar__user">
            {session.user.permissions.includes('APP_BUILDER') && <NotificationBell />}
            <span className="topbar__name">{session.user.displayName}</span>
            <button type="button" className="icon-button" aria-label="Change password" title="Change password" onClick={session.changePassword}>
              <KeyRound size={18} aria-hidden="true" />
            </button>
            <button type="button" className="icon-button" aria-label="Sign out" title="Sign out" onClick={session.signOut}>
              <LogOut size={18} aria-hidden="true" />
            </button>
          </div>
        )}
      </header>

      <div className="shell__body">
        <Sidebar open={sidebarOpen} onNavigate={() => setSidebarOpen(false)} />
        {sidebarOpen && <div className="scrim" onClick={() => setSidebarOpen(false)} aria-hidden="true" />}
        <main className="content">
          {problem && (
            <div className="notice" role="alert">
              <p>{problem}</p>
            </div>
          )}
          <Outlet />
        </main>
      </div>
    </div>
  );
}
