import type { ActiveStatus } from './containerTypes';
import { request, type Page } from './http';
import type { ItemRef } from './orders';
import type { ItemScan, UomQuantity } from './rf';

export type ReplenishmentStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type ReplenishmentReason = 'ORDERS_WAITING' | 'EMPTY' | 'BELOW_MINIMUM';

export const REPLENISHMENT_STATUSES: { value: ReplenishmentStatus; label: string }[] = [
  { value: 'OPEN', label: 'Waiting' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'COMPLETED', label: 'Done' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

export function replenishmentStatusLabel(status: ReplenishmentStatus): string {
  return REPLENISHMENT_STATUSES.find((s) => s.value === status)?.label ?? status;
}

const REASONS: Record<ReplenishmentReason, string> = {
  ORDERS_WAITING: 'Orders waiting',
  EMPTY: 'Empty',
  BELOW_MINIMUM: 'Below minimum',
};

export function reasonLabel(reason: ReplenishmentReason): string {
  return REASONS[reason] ?? reason;
}

interface LocationRef {
  id: string;
  locationId: string;
}

export interface ReplenishmentTask {
  id: string;
  taskNumber: string;
  status: ReplenishmentStatus;
  reason: ReplenishmentReason;
  item: ItemRef;
  /** What can be scanned for the item; only on the device. */
  scans: ItemScan[];
  lotNumber: string | null;
  expiryDate: string | null;
  fromLocation: LocationRef;
  /** The LPN to take it from, if it is in one. */
  fromLpn: string | null;
  toLocation: LocationRef;
  quantity: number;
  movedQuantity: number;
  shortQuantity: number;
  assignedTo: string | null;
  /** Why it was cancelled. */
  note: string | null;
  createdAt: string;
  startedAt: string | null;
  endedAt: string | null;
}

export interface ReplenishmentRule {
  id: string;
  item: ItemRef;
  location: LocationRef;
  /** In eaches, as are the rest. */
  minQuantity: number;
  maxQuantity: number;
  multiple: number;
  sourceZone: { id: string; code: string; name: string } | null;
  forOrders: boolean;
  status: ActiveStatus;
  /** Good stock there; this and the rest is null for an inactive rule. */
  onHand: number | null;
  reserved: number | null;
  coming: number | null;
  /** Orders are short of this much. */
  waiting: number | null;
  /** Still to find, over and above what is coming. */
  needed: number | null;
  version: number;
}

export interface ReplenishmentRuleInput {
  itemId: string;
  locationId: string;
  minQuantity: number;
  maxQuantity: number;
  multiple: number;
  sourceZoneId: string | null;
  forOrders: boolean;
  status: ActiveStatus;
  version?: number;
}

export interface RunResult {
  rules: number;
  quantity: number;
  /** Rules still below their minimum, with nothing more to bring. */
  still: number;
}

const API = '/api/replenishment';

export function listReplenishmentTasks(params: URLSearchParams, signal?: AbortSignal): Promise<Page<ReplenishmentTask>> {
  return request(`${API}/tasks?${params}`, { signal });
}

export function cancelReplenishment(id: string): Promise<ReplenishmentTask> {
  return request(`${API}/tasks/${id}/cancel`, { method: 'POST' });
}

export function runReplenishment(): Promise<RunResult> {
  return request(`${API}/run`, { method: 'POST' });
}

export function listReplenishmentRules(signal?: AbortSignal): Promise<ReplenishmentRule[]> {
  return request(`${API}/rules`, { signal });
}

export function saveReplenishmentRule(id: string | null, input: ReplenishmentRuleInput): Promise<ReplenishmentRule> {
  return request(id ? `${API}/rules/${id}` : `${API}/rules`, { method: id ? 'PUT' : 'POST', body: JSON.stringify(input) });
}

export function deleteReplenishmentRule(id: string): Promise<void> {
  return request(`${API}/rules/${id}`, { method: 'DELETE' });
}

// ---------- On a device ----------

export interface ReplenishState {
  task: ReplenishmentTask | null;
  waitingCount: number;
  /** Most urgent first. */
  waiting: ReplenishmentTask[];
}

export interface ReplenishConfirm {
  /** What the device showed as already moved, so sending it again moves nothing more. */
  movedSoFar: number;
  fromLocationId: string;
  lpnId: string | null;
  itemBarcode: string | null;
  /** In eaches. */
  quantity: number;
  /** The rest isn't there. */
  shortage: boolean;
  toLocationId: string | null;
  allowOver: boolean;
}

const RF = '/api/rf/replenishment';

export function getReplenish(signal?: AbortSignal): Promise<ReplenishState> {
  return request(RF, { signal });
}

/** The most urgent task if none is named. */
export function startReplenish(taskId: string | null): Promise<ReplenishState> {
  return request(`${RF}/start`, { method: 'POST', body: JSON.stringify({ taskId }) });
}

export function confirmReplenish(taskId: string, confirm: ReplenishConfirm): Promise<ReplenishState> {
  return request(`${RF}/tasks/${taskId}/confirm`, { method: 'POST', body: JSON.stringify(confirm) });
}

export function releaseReplenish(taskId: string): Promise<ReplenishState> {
  return request(`${RF}/tasks/${taskId}/release`, { method: 'POST' });
}

/** Eaches in the largest units that can be scanned, e.g. 27 at 6 per case is 4 CS and 3 EA. */
export function inUnits(eaches: number, scans: ItemScan[]): UomQuantity[] {
  const result: UomQuantity[] = [];
  let left = eaches;
  for (const scan of [...scans].sort((a, b) => b.eachesPerUnit - a.eachesPerUnit)) {
    const quantity = Math.floor(left / scan.eachesPerUnit);
    if (quantity > 0) {
      result.push({ uom: scan.uom, quantity, eachesPerUnit: scan.eachesPerUnit });
      left -= quantity * scan.eachesPerUnit;
    }
  }
  return result;
}
