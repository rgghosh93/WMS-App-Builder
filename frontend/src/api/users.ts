import type { ActiveStatus } from './containerTypes';
import type { FacilityRef } from './facilities';
import { request, type Page } from './http';
import type { Permission } from './session';

/** Someone who can sign in to devices, the desktop or both. Their PIN and password can be set but never read. */
export interface User {
  id: string;
  userCode: string;
  displayName: string;
  status: ActiveStatus;
  roles: { id: string; name: string }[];
  /** Where they work; everywhere if empty. */
  facilities?: FacilityRef[];
  /** Can sign in to devices, if a role allows it. */
  hasPin: boolean;
  /** Can sign in to the desktop, if a role allows it. */
  hasPassword: boolean;
  /** Set while too many wrong PINs or passwords keep them out. */
  lockedUntil: string | null;
  /** Places they are signed in. */
  devices: number;
  lastSignInAt: string | null;
  /** What they are in the middle of; only when one user is loaded. */
  inHand: string[];
  version: number;
}

export type UserSortField = 'userCode' | 'displayName' | 'status' | 'lastSignInAt';

export interface UserQuery {
  q: string;
  status: ActiveStatus | '';
  page: number;
  size: number;
  sort: UserSortField;
  dir: 'asc' | 'desc';
}

export function listUsers(query: UserQuery, signal?: AbortSignal): Promise<Page<User>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  params.set('sort', query.sort);
  params.set('dir', query.dir);
  return request(`/api/users?${params}`, { signal });
}

export function getUser(id: string, signal?: AbortSignal): Promise<User> {
  return request(`/api/users/${id}`, { signal });
}

export interface UserInput {
  userCode: string;
  displayName: string;
  status: ActiveStatus;
  roleIds: string[];
  /** Everywhere if empty. */
  facilityIds: string[];
}

/** A password given here only serves to choose their own. */
export function createUser(input: UserInput & { pin: string | null; password: string | null }): Promise<User> {
  return request('/api/users', { method: 'POST', body: JSON.stringify(input) });
}

export function updateUser(id: string, input: UserInput & { version: number }): Promise<User> {
  return request(`/api/users/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function setUserPin(id: string, pin: string): Promise<User> {
  return request(`/api/users/${id}/pin`, { method: 'PUT', body: JSON.stringify({ pin }) });
}

export function setUserPassword(id: string, password: string): Promise<User> {
  return request(`/api/users/${id}/password`, { method: 'PUT', body: JSON.stringify({ password }) });
}

export function unlockUser(id: string): Promise<User> {
  return request(`/api/users/${id}/unlock`, { method: 'POST', body: '{}' });
}

export function signOutUser(id: string): Promise<User> {
  return request(`/api/users/${id}/sign-out`, { method: 'POST', body: '{}' });
}

export function deleteUser(id: string): Promise<void> {
  return request(`/api/users/${id}`, { method: 'DELETE' });
}

// ---------- Roles ----------

/** A set of permissions. People can hold several roles. */
export interface Role {
  id: string;
  name: string;
  description: string | null;
  /** Has every permission, and can't be changed. */
  administrator: boolean;
  permissions: Permission[];
  userCount: number;
  version: number;
}

export interface PermissionInfo {
  id: Permission;
  group: 'DESKTOP' | 'DEVICE';
  label: string;
  description: string;
}

export interface RoleInput {
  name: string;
  description: string | null;
  permissions: Permission[];
  version?: number;
}

export function listRoles(signal?: AbortSignal): Promise<Role[]> {
  return request('/api/roles', { signal });
}

export function listPermissions(signal?: AbortSignal): Promise<PermissionInfo[]> {
  return request('/api/roles/permissions', { signal });
}

export function createRole(input: RoleInput): Promise<Role> {
  return request('/api/roles', { method: 'POST', body: JSON.stringify(input) });
}

export function updateRole(id: string, input: RoleInput): Promise<Role> {
  return request(`/api/roles/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteRole(id: string): Promise<void> {
  return request(`/api/roles/${id}`, { method: 'DELETE' });
}

/** Why a PIN can't be used, or null. The server checks the same. */
export function pinError(pin: string): string | null {
  if (!pin) return 'Enter a PIN';
  if (!/^[0-9]{4,12}$/.test(pin)) return 'Use 4 to 12 digits';
  const steps = [...pin].slice(1).map((digit, i) => Number(digit) - Number(pin[i]));
  if (Math.abs(steps[0]) <= 1 && steps.every((s) => s === steps[0])) return "Too easy to guess: don't repeat one digit or count up or down";
  return null;
}

/** A random PIN of six digits. */
export function randomPin(): string {
  for (;;) {
    const pin = Array.from(crypto.getRandomValues(new Uint32Array(6)), (n) => n % 10).join('');
    if (!pinError(pin)) return pin;
  }
}
