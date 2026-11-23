import { createContext, useContext, type ReactNode } from 'react';
import type { DesktopUser, Permission } from '../api/session';

export interface DesktopSession {
  user: DesktopUser;
  signOut: () => void;
  changePassword: () => void;
}

export const SessionContext = createContext<DesktopSession | null>(null);

export function useSession(): DesktopSession | null {
  return useContext(SessionContext);
}

/**
 * Whether the person signed in may do something, to leave out what they can't. The server decides;
 * this only keeps the screen honest.
 */
export function useCan(permission: Permission): boolean {
  const session = useContext(SessionContext);
  return session === null || session.user.permissions.includes(permission);
}

/** Shows its content only to people who may do something. */
export function Can({ permission, children }: { permission: Permission; children: ReactNode }) {
  return useCan(permission) ? <>{children}</> : null;
}
