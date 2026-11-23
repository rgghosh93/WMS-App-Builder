import { useState } from 'react';
import { Link, Outlet } from 'react-router-dom';
import { KeyRound, LogOut, Menu, Warehouse } from 'lucide-react';
import { useSession } from '../session/SessionContext';
import { Sidebar } from './Sidebar';

export function AppShell() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const session = useSession();

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
        {session && (
          <div className="topbar__user">
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
          <Outlet />
        </main>
      </div>
    </div>
  );
}
