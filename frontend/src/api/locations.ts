import { request, type Page } from './http';
import type { DimensionUom, VolumeUom, WeightUom } from './measure';

export { DIMENSION_UOMS, formatDimensions } from './measure';
export type { DimensionUom } from './measure';

export type LocationType = 'STORAGE';
export type IdentifierField = 'area' | 'zone' | 'aisle' | 'bay' | 'level' | 'position';

export const IDENTIFIER_FIELDS: IdentifierField[] = ['area', 'zone', 'aisle', 'bay', 'level', 'position'];

export const IDENTIFIER_LABELS: Record<IdentifierField, string> = {
  area: 'Area',
  zone: 'Zone',
  aisle: 'Aisle',
  bay: 'Bay',
  level: 'Level',
  position: 'Position',
};

export interface Location {
  id: string;
  locationId: string;
  type: LocationType;
  area: string | null;
  zone: string | null;
  aisle: string | null;
  bay: string | null;
  level: string | null;
  position: string | null;
  width: number;
  depth: number;
  height: number;
  dimensionUom: DimensionUom;
  maxWeight: number | null;
  maxWeightUom: WeightUom | null;
  maxVolume: number | null;
  maxVolumeUom: VolumeUom | null;
  maxLpns: number | null;
  maxSkus: number | null;
  maxUnits: number | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface LocationInput {
  locationId: string;
  type: LocationType;
  area: string;
  zone: string;
  aisle: string;
  bay: string;
  level: string;
  position: string;
  width: number;
  depth: number;
  height: number;
  dimensionUom: DimensionUom;
  maxWeight: number | null;
  maxWeightUom: WeightUom | null;
  maxVolume: number | null;
  maxVolumeUom: VolumeUom | null;
  maxLpns: number | null;
  maxSkus: number | null;
  maxUnits: number | null;
  version?: number;
}

export interface LocationTypeInfo {
  type: LocationType;
  label: string;
  requiredIdentifiers: IdentifierField[];
}

export type SortField = 'locationId' | 'type' | 'area' | 'zone' | 'aisle' | 'bay' | 'level' | 'position' | 'updatedAt';

export interface LocationQuery {
  q?: string;
  type?: LocationType | '';
  page: number;
  size: number;
  sort: SortField;
  dir: 'asc' | 'desc';
}

const BASE = '/api/locations';

export function listLocations(query: LocationQuery, signal?: AbortSignal): Promise<Page<Location>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.type) params.set('type', query.type);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  params.set('sort', query.sort);
  params.set('dir', query.dir);
  return request(`${BASE}?${params}`, { signal });
}

export function listLocationTypes(signal?: AbortSignal): Promise<LocationTypeInfo[]> {
  return request(`${BASE}/types`, { signal });
}

export function createLocation(input: LocationInput): Promise<Location> {
  return request(BASE, { method: 'POST', body: JSON.stringify(input) });
}

export function updateLocation(id: string, input: LocationInput): Promise<Location> {
  return request(`${BASE}/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteLocation(id: string): Promise<void> {
  return request(`${BASE}/${id}`, { method: 'DELETE' });
}
