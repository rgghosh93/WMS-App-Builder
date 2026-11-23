import type { ActiveStatus, ContainerCategory } from './containerTypes';
import { request, type Page } from './http';
import type { InventoryStatus } from './inventory';
import type { ItemRef } from './orders';

export type PutawayTaskStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type PutawayStrategy = 'FIXED' | 'ZONE' | 'NEAREST_EMPTY';
/** Whether the LPN is stored as it is, or its stock is put loose in the location. */
export type PutawayHandling = 'KEEP_LPN' | 'EMPTY_LPN';
export type OverrideReason = 'FULL' | 'BLOCKED' | 'DOES_NOT_FIT' | 'OTHER';

export const PUTAWAY_STATUSES: { value: PutawayTaskStatus; label: string }[] = [
  { value: 'OPEN', label: 'Waiting' },
  { value: 'IN_PROGRESS', label: 'In progress' },
  { value: 'COMPLETED', label: 'Put away' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

export function putawayStatusLabel(status: PutawayTaskStatus): string {
  return PUTAWAY_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export const STRATEGIES: { value: PutawayStrategy; label: string; hint: string }[] = [
  { value: 'FIXED', label: 'Fixed location', hint: 'Always this location, while it has room' },
  { value: 'ZONE', label: 'Put-away zone', hint: 'With the same stock if there is room, else an empty location, else anywhere in the zone with room' },
  { value: 'NEAREST_EMPTY', label: 'Nearest empty', hint: 'The first empty storage location in walking order (location ID order)' },
];

export const HANDLINGS: { value: PutawayHandling; label: string }[] = [
  { value: 'KEEP_LPN', label: 'Keep it in its LPN' },
  { value: 'EMPTY_LPN', label: 'Empty the LPN into the location' },
];

export const OVERRIDE_REASONS: { value: OverrideReason; label: string }[] = [
  { value: 'FULL', label: 'It is full' },
  { value: 'BLOCKED', label: "Can't reach it" },
  { value: 'DOES_NOT_FIT', label: "It doesn't fit" },
  { value: 'OTHER', label: 'Another reason' },
];

export function overrideReasonLabel(reason: OverrideReason): string {
  return OVERRIDE_REASONS.find((r) => r.value === reason)?.label ?? reason;
}

interface LocationRef {
  id: string;
  locationId: string;
}

export interface PutawayLine {
  item: ItemRef;
  quantity: number;
  lotNumber: string | null;
  expiryDate: string | null;
  status: InventoryStatus;
}

export interface PutawayTask {
  id: string;
  taskNumber: string;
  status: PutawayTaskStatus;
  lpn: { id: string; lpnId: string; containerType: string };
  fromLocation: LocationRef;
  /** Where it should go; null until started, or if no rule found room. */
  directedLocation: LocationRef | null;
  ruleName: string | null;
  handling: PutawayHandling | null;
  /** Where it went. */
  toLocation: LocationRef | null;
  overrideReason: OverrideReason | null;
  assignedTo: string | null;
  /** What is in it now, or what was put away. */
  lines: PutawayLine[];
  totalQuantity: number;
  skips: { location: LocationRef; reason: OverrideReason; skippedBy: string; skippedAt: string }[];
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface PutawayTaskQuery {
  q: string;
  status: PutawayTaskStatus | '';
  page: number;
  size: number;
}

export function listPutawayTasks(query: PutawayTaskQuery, signal?: AbortSignal): Promise<Page<PutawayTask>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/putaway/tasks?${params}`, { signal });
}

export function getPutawayTask(id: string, signal?: AbortSignal): Promise<PutawayTask> {
  return request(`/api/putaway/tasks/${id}`, { signal });
}

// ---------- Rules ----------

export interface PutawayRule {
  id: string;
  sequence: number;
  name: string;
  status: ActiveStatus;
  item: ItemRef | null;
  containerCategory: ContainerCategory | null;
  stockStatus: InventoryStatus | null;
  strategy: PutawayStrategy;
  location: LocationRef | null;
  /** A put-away zone. */
  zone: { id: string; code: string; name: string } | null;
  handling: PutawayHandling;
  version: number;
}

export interface PutawayRuleInput {
  sequence: number;
  name: string;
  status: ActiveStatus;
  itemId: string | null;
  containerCategory: ContainerCategory | null;
  stockStatus: InventoryStatus | null;
  strategy: PutawayStrategy;
  locationId: string | null;
  zoneId: string | null;
  handling: PutawayHandling;
  version?: number;
}

export function listPutawayRules(signal?: AbortSignal): Promise<PutawayRule[]> {
  return request('/api/putaway/rules', { signal });
}

export function createPutawayRule(input: PutawayRuleInput): Promise<PutawayRule> {
  return request('/api/putaway/rules', { method: 'POST', body: JSON.stringify(input) });
}

export function updatePutawayRule(id: string, input: PutawayRuleInput): Promise<PutawayRule> {
  return request(`/api/putaway/rules/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deletePutawayRule(id: string): Promise<void> {
  return request(`/api/putaway/rules/${id}`, { method: 'DELETE' });
}

// ---------- On a mobile device ----------

export interface PutawayState {
  /** What this person is putting away, or null. */
  task: PutawayTask | null;
  waitingCount: number;
  waiting: PutawayTask[];
}

export interface PutawayConfirm {
  locationId: string;
  /** Why not the directed location, if it is another one. */
  reason: OverrideReason | null;
  allowOver: boolean;
  handling: PutawayHandling | null;
}

const RF = '/api/rf/putaway';

function post<T>(path: string, body: unknown): Promise<T> {
  return request(`${RF}/${path}`, { method: 'POST', body: JSON.stringify(body) });
}

export function getPutaway(signal?: AbortSignal): Promise<PutawayState> {
  return request(RF, { signal });
}

export function startPutaway(lpnId: string, takeOver = false): Promise<PutawayState> {
  return post('start', { lpnId, takeOver });
}

/** The directed location can't be used: ask for another. */
export function redirectPutaway(taskId: string, locationId: string, reason: OverrideReason): Promise<PutawayState> {
  return post(`tasks/${taskId}/redirect`, { locationId, reason });
}

export function confirmPutaway(taskId: string, confirm: PutawayConfirm): Promise<PutawayState> {
  return post(`tasks/${taskId}/confirm`, confirm);
}

export function releasePutaway(taskId: string): Promise<PutawayState> {
  return post(`tasks/${taskId}/release`, {});
}
