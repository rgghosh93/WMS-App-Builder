import { request } from './http';
import type { ItemRef, PickMethod } from './orders';
import type { Permission } from './session';

/** The person signed in on this device. */
export interface DeviceUser {
  id: string;
  userCode: string;
  displayName: string;
  /** What they may do on devices. */
  permissions?: Permission[];
}

export function currentUser(signal?: AbortSignal): Promise<DeviceUser> {
  return request('/api/rf/session', { signal });
}

export function signIn(userCode: string, pin: string): Promise<DeviceUser> {
  return request('/api/rf/session', { method: 'POST', body: JSON.stringify({ userCode, pin }) });
}

export function signOut(): Promise<void> {
  return request('/api/rf/session', { method: 'DELETE' });
}

export type PickingStep = 'SCAN_CONTAINER' | 'CHOOSE_CONTAINER_TYPE' | 'PICK' | 'DROP';

export interface TaskProgress {
  id: string;
  taskNumber: string;
  pickMethod: PickMethod;
  /** Code of its pick zone. */
  pickZone: string | null;
  orderCount: number;
  toLpnId: string;
  toContainerType: string;
  closedDetails: number;
  detailCount: number;
  /** Nothing is picked yet, so the task can be given back. */
  canRelease: boolean;
}

export interface UomQuantity {
  uom: string;
  quantity: number;
  eachesPerUnit: number;
}

/** A barcode or ID that identifies the item, and the unit it stands for. */
export interface ItemScan {
  value: string;
  uom: string;
  eachesPerUnit: number;
}

export interface PickDirective {
  detailId: string;
  detailNumber: number;
  orderNumber: string;
  customerName: string;
  locationId: string;
  fromLpnId: string | null;
  item: ItemRef;
  lotNumber: string | null;
  expiryDate: string | null;
  serialNumber: string | null;
  quantity: number;
  pickedQuantity: number;
  remaining: number;
  /** The remaining quantity in the largest units that can be scanned. */
  directed: UomQuantity[];
  itemScans: ItemScan[];
}

export interface ContainerTypeOption {
  id: string;
  containerTypeId: string;
  description: string;
}

/** Where the picker is, and what to do next. Only the part for the current step is set. */
export interface PickingState {
  step: PickingStep;
  waitingTasks: number;
  task: TaskProgress | null;
  pick: PickDirective | null;
  drop: { suggestedLocationId: string | null } | null;
  newContainer: { lpnId: string; types: ContainerTypeOption[] } | null;
}

export interface PickConfirmation {
  detailId: string;
  /** What the device showed as already picked, so a repeated confirm isn't picked twice. */
  pickedSoFar: number;
  locationId: string;
  fromLpnId: string | null;
  itemBarcode: string | null;
  serialNumber: string | null;
  /** In the unit of the scanned barcode. */
  quantity: number;
  /** There is no more to pick: close the pick with what was found. */
  shortPick: boolean;
}

export function getPicking(signal?: AbortSignal): Promise<PickingState> {
  return request('/api/rf/picking', { signal });
}

function post<T>(path: string, body: unknown): Promise<T> {
  return request(`/api/rf/picking/${path}`, { method: 'POST', body: JSON.stringify(body) });
}

/** With takeOver, carries on with the task someone else is picking into the container. With zoneId, only tasks of that pick zone; with taskId, that task. */
export function startPicking(lpnId: string, containerTypeId: string | null = null, takeOver = false, zoneId?: string, taskId?: string): Promise<PickingState> {
  return post('start', { lpnId, containerTypeId, takeOver, zoneId, taskId });
}

export interface PickZone {
  id: string;
  code: string;
  name: string;
  /** Tasks nobody has started there. */
  waiting: number;
}

export function listPickZones(signal?: AbortSignal): Promise<PickZone[]> {
  return request('/api/rf/picking/zones', { signal });
}

export function confirmPick(pick: PickConfirmation): Promise<PickingState> {
  return post('confirm', pick);
}

export function dropContainer(locationId: string): Promise<PickingState> {
  return post('drop', { locationId });
}

export function releaseTask(): Promise<PickingState> {
  return post('release', {});
}

/** Whether a scan is the expected label; case and stray spaces don't matter. */
export function scanMatches(scanned: string, expected: string): boolean {
  return scanned.trim().toUpperCase() === expected.trim().toUpperCase();
}

/** "4 CS + 3 EA" */
export function formatUnits(units: UomQuantity[]): string {
  return units.map((u) => `${u.quantity.toLocaleString()} ${u.uom}`).join(' + ');
}
