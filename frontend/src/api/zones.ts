import type { ActiveStatus } from './containerTypes';
import { request, type Page } from './http';
import type { Location } from './locations';

/** What a zone is for. Not the zone in a location's address. */
export type ZoneFunction = 'ALLOCATION' | 'PICKING' | 'PUTAWAY' | 'COUNTING' | 'REPLENISHMENT';

export const ZONE_FUNCTIONS: { value: ZoneFunction; label: string; hint: string }[] = [
  { value: 'ALLOCATION', label: 'Allocation', hint: 'Which stock orders get first, and which they never get' },
  { value: 'PICKING', label: 'Picking', hint: 'Picks are split into one task per zone, and people can pick in one zone' },
  { value: 'PUTAWAY', label: 'Put-away', hint: 'Put-away rules send stock to a zone' },
  { value: 'COUNTING', label: 'Counting', hint: 'Counts can be planned for a zone' },
  { value: 'REPLENISHMENT', label: 'Replenishment', hint: 'Replenishment rules bring stock from a zone' },
];

export function zoneFunctionLabel(value: ZoneFunction): string {
  return ZONE_FUNCTIONS.find((f) => f.value === value)?.label ?? value;
}

export interface Zone {
  id: string;
  function: ZoneFunction;
  code: string;
  name: string;
  status: ActiveStatus;
  /** For allocation: lower is used first. */
  priority: number | null;
  /** For allocation: whether orders get stock from it at all. */
  allocatable: boolean;
  locationCount: number;
  version: number;
}

export interface ZoneInput {
  function: ZoneFunction;
  code: string;
  name: string;
  status: ActiveStatus;
  priority: number | null;
  allocatable: boolean;
  version?: number;
}

/** Which storage locations: by ID, or by address. Parts left out match anything. */
export interface LocationFilter {
  locationIds?: string[];
  area?: string;
  zone?: string;
  aisleFrom?: string;
  aisleTo?: string;
  bayFrom?: string;
  bayTo?: string;
  level?: string;
}

export interface AddPreview {
  matching: number;
  already: number;
  /** In other zones of this function: how many, by the zone's code. */
  elsewhere: Record<string, number>;
}

export interface ZoneOfLocation {
  function: ZoneFunction;
  label: string;
  zoneId: string;
  code: string;
  name: string;
  status: ActiveStatus;
}

const API = '/api/zones';

const post = <T>(path: string, body: unknown, signal?: AbortSignal) =>
  request<T>(`${API}${path}`, { method: 'POST', body: JSON.stringify(body), signal });

export function listZones(fn: ZoneFunction | '' = '', signal?: AbortSignal): Promise<Zone[]> {
  return request(fn ? `${API}?function=${fn}` : API, { signal });
}

export function getZone(id: string, signal?: AbortSignal): Promise<Zone> {
  return request(`${API}/${id}`, { signal });
}

export function createZone(input: ZoneInput): Promise<Zone> {
  return post('', input);
}

export function updateZone(id: string, input: ZoneInput): Promise<Zone> {
  return request(`${API}/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteZone(id: string): Promise<void> {
  return request(`${API}/${id}`, { method: 'DELETE' });
}

export function listZoneLocations(id: string, params: URLSearchParams, signal?: AbortSignal): Promise<Page<Location>> {
  return request(`${API}/${id}/locations?${params}`, { signal });
}

export function previewLocations(id: string, filter: LocationFilter, signal?: AbortSignal): Promise<AddPreview> {
  return post(`/${id}/locations/preview`, filter, signal);
}

/** With move, takes locations from other zones of the function. */
export function addLocations(id: string, filter: LocationFilter, move: boolean): Promise<AddPreview> {
  return post(`/${id}/locations`, { filter, move });
}

export function removeLocations(id: string, locationIds: string[]): Promise<Zone> {
  return post(`/${id}/locations/remove`, { locationIds });
}

export function listLocationZones(locationId: string, signal?: AbortSignal): Promise<ZoneOfLocation[]> {
  return request(`/api/locations/${locationId}/zones`, { signal });
}
