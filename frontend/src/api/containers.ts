import { request, type Page } from './http';
import type { ContainerCategory } from './containerTypes';

export type ContainerStatus = 'AVAILABLE' | 'IN_USE' | 'ON_HOLD' | 'RETIRED';

export const CONTAINER_STATUSES: { value: ContainerStatus; label: string; hint: string }[] = [
  { value: 'AVAILABLE', label: 'Available', hint: 'Empty and ready to use' },
  { value: 'IN_USE', label: 'In use', hint: 'Holding inventory or assigned to work' },
  { value: 'ON_HOLD', label: 'On hold', hint: 'Damaged, being cleaned or not usable for now' },
  { value: 'RETIRED', label: 'Retired', hint: 'Taken out of service' },
];

export function containerStatusLabel(status: ContainerStatus): string {
  return CONTAINER_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export interface LocationRef {
  id: string;
  locationId: string;
}

export interface Container {
  id: string;
  lpnId: string;
  containerType: { id: string; containerTypeId: string; description: string; category: ContainerCategory };
  status: ContainerStatus;
  currentLocation: LocationRef | null;
  /** The container this one is inside, if any. */
  parentContainer: { id: string; lpnId: string } | null;
  /** Facility-owned (true) or created with inventory (false). */
  permanent: boolean;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface ContainerInput {
  lpnId: string;
  containerTypeId: string;
  status: ContainerStatus;
  currentLocationId: string | null;
  notes: string | null;
  version?: number;
}

export interface ContainerRangeInput {
  prefix: string;
  start: number;
  count: number;
  digits: number;
  containerTypeId: string;
  status: ContainerStatus;
  currentLocationId: string | null;
  notes: string | null;
}

export interface ContainerRangeResult {
  created: number;
  firstLpnId: string;
  lastLpnId: string;
}

export const MAX_RANGE = 1000;

export type ContainerSortField = 'lpnId' | 'containerType' | 'status' | 'location' | 'updatedAt';

export interface ContainerQuery {
  q: string;
  containerTypeId: string;
  status: ContainerStatus | '';
  page: number;
  size: number;
  sort: ContainerSortField;
  dir: 'asc' | 'desc';
}

const BASE = '/api/containers';

export function listContainers(query: ContainerQuery, signal?: AbortSignal): Promise<Page<Container>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.containerTypeId) params.set('containerTypeId', query.containerTypeId);
  if (query.status) params.set('status', query.status);
  // This page manages facility-owned containers; LPNs created with inventory show in the inventory views.
  params.set('permanent', 'true');
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  params.set('sort', query.sort);
  params.set('dir', query.dir);
  return request(`${BASE}?${params}`, { signal });
}

export function createContainer(input: ContainerInput): Promise<Container> {
  return request(BASE, { method: 'POST', body: JSON.stringify(input) });
}

export function createContainerRange(input: ContainerRangeInput): Promise<ContainerRangeResult> {
  return request(`${BASE}/range`, { method: 'POST', body: JSON.stringify(input) });
}

export function updateContainer(id: string, input: ContainerInput): Promise<Container> {
  return request(`${BASE}/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteContainer(id: string): Promise<void> {
  return request(`${BASE}/${id}`, { method: 'DELETE' });
}

/** The LPN a range would produce for a number, e.g. ("TOTE", 7, 5) → "TOTE00007". */
export function rangeLpn(prefix: string, n: number, digits: number): string {
  return prefix.trim().toUpperCase() + String(n).padStart(digits, '0');
}
