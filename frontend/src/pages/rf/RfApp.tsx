import { Link, Navigate, Route, Routes } from 'react-router-dom';
import { ArrowDownToLine, ArrowLeftRight, ChevronRight, ClipboardList, FastForward, Hand, LogOut, Menu as MenuIcon, Package, PackageOpen, RefreshCw, Truck, type LucideIcon } from 'lucide-react';
import type { DeviceUser } from '../../api/rf';
import type { Permission } from '../../api/session';
import { CountTransaction } from './CountTransaction';
import { LoadingTransaction } from './LoadingTransaction';
import { MoveTransaction } from './MoveTransaction';
import { NextTransaction } from './NextTransaction';
import { PickingTransaction } from './PickingTransaction';
import { PutawayTransaction } from './PutawayTransaction';
import { ReplenishTransaction } from './ReplenishTransaction';
import { ReceivingTransaction } from './ReceivingTransaction';
import { SessionGate } from './SessionGate';

/** The mobile device app: sign in, choose a transaction, work through it. */
export function RfApp() {
  return (
    <SessionGate>
      {({ user, leave, onSignedOut, signOutError }) => (
        <div className="rf">
          <header className="rf-bar">
            <Link to="/rf" className="rf-bar__button" aria-label="Menu">
              <MenuIcon size={22} aria-hidden="true" />
            </Link>
            <span className="rf-bar__user">{user.displayName}</span>
            {mayUse(user, 'DEVICE_NEXT') && (
              <Link to="/rf/next" className="rf-bar__button" aria-label="Next task">
                <FastForward size={20} aria-hidden="true" />
              </Link>
            )}
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
              <Route index element={<TransactionMenu user={user} />} />
              <Route path="next" element={<NextTransaction onSignedOut={onSignedOut} />} />
              <Route path="picking" element={<PickingTransaction onSignedOut={onSignedOut} />} />
              <Route path="receiving" element={<ReceivingTransaction onSignedOut={onSignedOut} />} />
              <Route path="putaway" element={<PutawayTransaction onSignedOut={onSignedOut} />} />
              <Route path="replenish" element={<ReplenishTransaction onSignedOut={onSignedOut} />} />
              <Route path="move" element={<MoveTransaction onSignedOut={onSignedOut} />} />
              <Route path="count" element={<CountTransaction onSignedOut={onSignedOut} />} />
              <Route path="loading" element={<LoadingTransaction onSignedOut={onSignedOut} />} />
              <Route path="*" element={<Navigate to="/rf" replace />} />
            </Routes>
          </main>
        </div>
      )}
    </SessionGate>
  );
}

const TRANSACTIONS: { permission: Permission; to: string; label: string; icon: LucideIcon }[] = [
  { permission: 'DEVICE_NEXT', to: '/rf/next', label: 'Next task', icon: FastForward },
  { permission: 'DEVICE_RECEIVE', to: '/rf/receiving', label: 'Receiving', icon: PackageOpen },
  { permission: 'DEVICE_PUTAWAY', to: '/rf/putaway', label: 'Put-away', icon: ArrowDownToLine },
  { permission: 'DEVICE_REPLENISH', to: '/rf/replenish', label: 'Replenish', icon: RefreshCw },
  { permission: 'DEVICE_MOVE', to: '/rf/move', label: 'Move', icon: ArrowLeftRight },
  { permission: 'DEVICE_COUNT', to: '/rf/count', label: 'Count', icon: ClipboardList },
  { permission: 'DEVICE_PICK', to: '/rf/picking', label: 'Picking', icon: Hand },
  { permission: 'DEVICE_PACK', to: '/pack', label: 'Packing', icon: Package },
  { permission: 'DEVICE_LOAD', to: '/rf/loading', label: 'Loading', icon: Truck },
];

function mayUse(user: DeviceUser, permission: Permission): boolean {
  return !user.permissions || user.permissions.includes(permission);
}

/** The transactions this person may use. */
function TransactionMenu({ user }: { user: DeviceUser }) {
  const allowed = TRANSACTIONS.filter((t) => mayUse(user, t.permission));
  return (
    <>
      <h1 className="rf-title">Transactions</h1>
      <nav className="rf-choices" aria-label="Transactions">
        {allowed.map(({ to, label, icon: Icon }) => (
          <Link key={to} to={to} className="rf-menu-item">
            <Icon size={24} aria-hidden="true" />
            <span>{label}</span>
            <ChevronRight size={20} aria-hidden="true" />
          </Link>
        ))}
      </nav>
      {allowed.length === 0 && <p className="muted">You don't have access to anything here. Ask an administrator.</p>}
    </>
  );
}
