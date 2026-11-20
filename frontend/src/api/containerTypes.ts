import { request, type Page } from './http';
import type { DimensionUom, VolumeUom, WeightUom } from './measure';

export type ContainerCategory = 'PALLET' | 'CARTON' | 'TOTE' | 'BIN' | 'CASE' | 'CAGE' | 'OTHER';
export type ActiveStatus = 'ACTIVE' | 'INACTIVE';

export const CONTAINER_CATEGORIES: { value: ContainerCategory; label: string; reusableByDefault: boolean }[] = [
  { value: 'PALLET', label: 'Pallet', reusableByDefault: true },
  { value: 'CARTON', label: 'Carton', reusableByDefault: false },
  { value: 'TOTE', label: 'Tote', reusableByDefault: true },
  { value: 'BIN', label: 'Bin', reusableByDefault: true },
  { value: 'CASE', label: 'Case', reusableByDefault: false },
  { value: 'CAGE', label: 'Cage', reusableByDefault: true },
  { value: 'OTHER', label: 'Other', reusableByDefault: false },
];

export const ACTIVE_STATUSES: { value: ActiveStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
];

export function categoryLabel(category: ContainerCategory): string {
  return CONTAINER_CATEGORIES.find((c) => c.value === category)?.label ?? category;
}

export interface ContainerType {
  id: string;
  containerTypeId: string;
  description: string;
  category: ContainerCategory;
  status: ActiveStatus;
  reusable: boolean;
  width: number;
  depth: number;
  height: number;
  dimensionUom: DimensionUom;
  tareWeight: number | null;
  tareWeightUom: WeightUom | null;
  maxWeight: number | null;
  maxWeightUom: WeightUom | null;
  maxVolume: number | null;
  maxVolumeUom: VolumeUom | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export type ContainerTypeInput = Omit<ContainerType, 'id' | 'createdAt' | 'updatedAt' | 'version'> & { version?: number };

export type ContainerTypeSortField = 'containerTypeId' | 'description' | 'category' | 'status' | 'updatedAt';

export interface ContainerTypeQuery {
  q: string;
  category: ContainerCategory | '';
  status: ActiveStatus | '';
  page: number;
  size: number;
  sort: ContainerTypeSortField;
  dir: 'asc' | 'desc';
}

const BASE = '/api/container-types';

export function listContainerTypes(query: ContainerTypeQuery, signal?: AbortSignal): Promise<Page<ContainerType>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.category) params.set('category', query.category);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  params.set('sort', query.sort);
  params.set('dir', query.dir);
  return request(`${BASE}?${params}`, { signal });
}

export function createContainerType(input: ContainerTypeInput): Promise<ContainerType> {
  return request(BASE, { method: 'POST', body: JSON.stringify(input) });
}

export function updateContainerType(id: string, input: ContainerTypeInput): Promise<ContainerType> {
  return request(`${BASE}/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteContainerType(id: string): Promise<void> {
  return request(`${BASE}/${id}`, { method: 'DELETE' });
}
