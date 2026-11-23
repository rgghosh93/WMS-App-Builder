import { request, type Page } from './http';
import type { WeightUom } from './measure';
import type { ItemRef, OrderRef } from './orders';
import type { ContainerTypeOption, ItemScan } from './rf';

export type PackTaskStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED';
export type CartonStatus = 'OPEN' | 'CLOSED';

export const PACK_STATUSES: { value: PackTaskStatus; label: string }[] = [
  { value: 'OPEN', label: 'Waiting' },
  { value: 'IN_PROGRESS', label: 'Packing' },
  { value: 'COMPLETED', label: 'Packed' },
];

export function packStatusLabel(status: PackTaskStatus): string {
  return PACK_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export interface PackStation {
  id: string;
  locationId: string;
  waitingTasks: number;
}

/** Something in the container to pack. */
export interface PackDetail {
  id: string;
  detailNumber: number;
  order: OrderRef;
  lineNumber: number;
  item: ItemRef;
  lotNumber: string | null;
  serialNumber: string | null;
  quantity: number;
  packedQuantity: number;
  /** Reported missing at packing. */
  shortQuantity: number;
  remaining: number;
  itemScans: ItemScan[];
}

export interface CartonLine {
  detailId: string;
  lineNumber: number;
  item: ItemRef;
  lotNumber: string | null;
  serialNumber: string | null;
  quantity: number;
}

/** A shipping carton, for one order. */
export interface Carton {
  id: string;
  container: { id: string; lpnId: string };
  containerType: string;
  order: OrderRef;
  status: CartonStatus;
  weight: number | null;
  weightUom: WeightUom | null;
  totalQuantity: number;
  contents: CartonLine[];
  closedAt: string | null;
  closedBy: string | null;
  trackingNumber: string | null;
  loadedAt: string | null;
}

/** Packing one container dropped at a pack station. */
export interface PackTask {
  id: string;
  taskNumber: string;
  status: PackTaskStatus;
  station: { id: string; locationId: string };
  /** The container being packed out of. */
  container: { id: string; lpnId: string };
  containerType: string;
  pickTask: { id: string; taskNumber: string };
  orders: OrderRef[];
  assignedTo: string | null;
  assignedToId: string | null;
  totalQuantity: number;
  packedQuantity: number;
  shortQuantity: number;
  /** The container is itself a carton holding one order, so it can ship without repacking. */
  canPackAsIs: boolean;
  details: PackDetail[];
  cartons: Carton[];
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

// ---------- Looking at packing work ----------

export interface PackTaskQuery {
  q: string;
  status: PackTaskStatus | '';
  stationId: string;
  page: number;
  size: number;
}

export function listPackTasks(query: PackTaskQuery, signal?: AbortSignal): Promise<Page<PackTask>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  if (query.stationId) params.set('stationId', query.stationId);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/pack-tasks?${params}`, { signal });
}

export function getPackTask(id: string, signal?: AbortSignal): Promise<PackTask> {
  return request(`/api/pack-tasks/${id}`, { signal });
}

export function listPackStations(signal?: AbortSignal): Promise<PackStation[]> {
  return request('/api/pack-tasks/stations', { signal });
}

// ---------- At the pack station (signed in) ----------

const BASE = '/api/rf/packing';

function post(path: string, body: unknown = {}): Promise<PackTask> {
  return request(`${BASE}/${path}`, { method: 'POST', body: JSON.stringify(body) });
}

export function stationList(signal?: AbortSignal): Promise<PackStation[]> {
  return request(`${BASE}/stations`, { signal });
}

export function stationQueue(
  locationId: string,
  signal?: AbortSignal,
): Promise<{ station: { id: string; locationId: string }; tasks: PackTask[] }> {
  return request(`${BASE}/stations/${encodeURIComponent(locationId)}`, { signal });
}

export function listCartonTypes(signal?: AbortSignal): Promise<ContainerTypeOption[]> {
  return request(`${BASE}/carton-types`, { signal });
}

export function reloadPackTask(id: string): Promise<PackTask> {
  return request(`${BASE}/tasks/${id}`);
}

export function startPacking(locationId: string, lpnId: string, takeOver = false): Promise<PackTask> {
  return post('start', { locationId, lpnId, takeOver });
}

/** Opens a carton for an order. Without an LPN, one is made. */
export function openCarton(taskId: string, orderId: string, containerTypeId: string, lpnId: string | null): Promise<PackTask> {
  return post(`tasks/${taskId}/cartons`, { orderId, containerTypeId, lpnId });
}

export interface PackScan {
  cartonId: string;
  /** What the station showed as packed, so a repeated scan isn't packed twice. */
  packedSoFar: number;
  itemBarcode: string;
  serialNumber: string | null;
  lotNumber: string | null;
  /** In the unit of the scanned barcode. */
  quantity: number;
}

export function packItem(taskId: string, scan: PackScan): Promise<PackTask> {
  return post(`tasks/${taskId}/pack`, scan);
}

export function emptyCarton(taskId: string, cartonId: string): Promise<PackTask> {
  return post(`tasks/${taskId}/cartons/${cartonId}/empty`);
}

export interface CartonWeight {
  weight: number | null;
  weightUom: WeightUom | null;
}

export function closeCarton(taskId: string, cartonId: string, weight: CartonWeight): Promise<PackTask> {
  return post(`tasks/${taskId}/cartons/${cartonId}/close`, weight);
}

export function packAsIs(taskId: string, weight: CartonWeight): Promise<PackTask> {
  return post(`tasks/${taskId}/pack-as-is`, weight);
}

export function reportMissing(taskId: string, detailId: string, remaining: number): Promise<PackTask> {
  return post(`tasks/${taskId}/missing`, { detailId, remaining });
}
