import type { FacilityRef } from './facilities';
import { request } from './http';

export type Permission =
  | 'DESKTOP'
  | 'MASTER_DATA'
  | 'INBOUND'
  | 'PUTAWAY_RULES'
  | 'REPLENISHMENT'
  | 'TASKS'
  | 'ORDERS'
  | 'SHIPPING'
  | 'INVENTORY'
  | 'INVENTORY_APPROVE'
  | 'FACILITIES'
  | 'USERS'
  | 'DEVICE_NEXT'
  | 'DEVICE_RECEIVE'
  | 'DEVICE_PUTAWAY'
  | 'DEVICE_REPLENISH'
  | 'DEVICE_MOVE'
  | 'DEVICE_COUNT'
  | 'DEVICE_PICK'
  | 'DEVICE_PACK'
  | 'DEVICE_LOAD';

/** The person signed in to the desktop. */
export interface DesktopUser {
  id: string;
  userCode: string;
  displayName: string;
  permissions: Permission[];
  /** Someone else set their password, so they have to choose their own first. */
  mustChangePassword: boolean;
  /** Where they are working. */
  facility?: FacilityRef;
  /** Everywhere they may. */
  facilities?: FacilityRef[];
}

export interface SessionView {
  state: 'SIGNED_IN' | 'SIGNED_OUT' | 'SETUP';
  user: DesktopUser | null;
  /** Whether the first administrator may be made from here. */
  setupHere: boolean;
  /** Whether it may be made from anywhere with the setup key, as in the cloud. */
  setupWithKey?: boolean;
}

export function getSession(signal?: AbortSignal): Promise<SessionView> {
  return request('/api/session', { signal });
}

export function signInToDesktop(userCode: string, password: string): Promise<DesktopUser> {
  return request('/api/session', { method: 'POST', body: JSON.stringify({ userCode, password }) });
}

/** @param setupKey needed unless this is the computer the WMS runs on */
export function setUpAdministrator(userCode: string, displayName: string, password: string, setupKey?: string): Promise<DesktopUser> {
  return request('/api/session/setup', { method: 'POST', body: JSON.stringify({ userCode, displayName, password, setupKey }) });
}

export function changePassword(currentPassword: string, newPassword: string): Promise<DesktopUser> {
  return request('/api/session/password', { method: 'POST', body: JSON.stringify({ currentPassword, newPassword }) });
}

/** Goes on working in another facility. */
export function goToFacility(facilityId: string): Promise<DesktopUser> {
  return request('/api/session/facility', { method: 'PUT', body: JSON.stringify({ facilityId }) });
}

export function signOutOfDesktop(): Promise<void> {
  return request('/api/session', { method: 'DELETE' });
}

export const MIN_PASSWORD = 10;

/** A random password to start with: four groups of four, without characters that look alike. */
export function randomPassword(): string {
  const letters = 'abcdefghjkmnpqrstuvwxyz23456789';
  const pick = Array.from(crypto.getRandomValues(new Uint32Array(16)), (n) => letters[n % letters.length]).join('');
  return pick.replace(/(.{4})(?=.)/g, '$1-');
}
