import { request, type Page } from './http';
import type { DimensionUom, WeightUom } from './measure';
import type { OwnerRef } from './owners';
import type { UomKind } from './uoms';

export type ItemStatus = 'ACTIVE' | 'INACTIVE';

export const ITEM_STATUSES: { value: ItemStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
];

export interface Item {
  id: string;
  /** Whose it is. */
  owner?: OwnerRef | null;
  itemId: string;
  description: string;
  status: ItemStatus;
  barcode: string | null;
  lotControlled: boolean;
  serialControlled: boolean;
  expiryControlled: boolean;
  shelfLifeDays: number | null;
  width: number | null;
  depth: number | null;
  height: number | null;
  dimensionUom: DimensionUom | null;
  weight: number | null;
  weightUom: WeightUom | null;
  /** Packaging levels above the each, smallest first. */
  uoms: ItemUom[];
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** One packaging level of an item, e.g. 1 CS = 12 EA. */
export interface ItemUom {
  id: string;
  uom: { id: string; uomCode: string; description: string; kind: UomKind };
  /** How many eaches one of this unit holds. */
  quantity: number;
  barcode: string | null;
  width: number | null;
  depth: number | null;
  height: number | null;
  dimensionUom: DimensionUom | null;
  weight: number | null;
  weightUom: WeightUom | null;
}

export interface ItemUomInput {
  uomId: string;
  quantity: number;
  barcode: string | null;
  width: number | null;
  depth: number | null;
  height: number | null;
  dimensionUom: DimensionUom | null;
  weight: number | null;
  weightUom: WeightUom | null;
}

/** "CS 12 · PL 480": each packaging level and how many eaches it holds. */
export function packagingSummary(uoms: ItemUom[]): string {
  return uoms.map((p) => `${p.uom.uomCode} ${p.quantity.toLocaleString()}`).join(' · ');
}

export interface ItemInput {
  /** Code of whose it is; the first owner if left out. */
  owner?: string;
  itemId: string;
  description: string;
  status: ItemStatus;
  barcode: string | null;
  lotControlled: boolean;
  serialControlled: boolean;
  expiryControlled: boolean;
  shelfLifeDays: number | null;
  width: number | null;
  depth: number | null;
  height: number | null;
  dimensionUom: DimensionUom | null;
  weight: number | null;
  weightUom: WeightUom | null;
  uoms: ItemUomInput[];
  version?: number;
}

export type ItemSortField = 'itemId' | 'description' | 'status' | 'barcode' | 'updatedAt';

export interface ItemQuery {
  q: string;
  status: ItemStatus | '';
  owner?: string;
  page: number;
  size: number;
  sort: ItemSortField;
  dir: 'asc' | 'desc';
}

const BASE = '/api/items';

export function listItems(query: ItemQuery, signal?: AbortSignal): Promise<Page<Item>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  if (query.owner) params.set('owner', query.owner);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  params.set('sort', query.sort);
  params.set('dir', query.dir);
  return request(`${BASE}?${params}`, { signal });
}

export function createItem(input: ItemInput): Promise<Item> {
  return request(BASE, { method: 'POST', body: JSON.stringify(input) });
}

export function updateItem(id: string, input: ItemInput): Promise<Item> {
  return request(`${BASE}/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteItem(id: string): Promise<void> {
  return request(`${BASE}/${id}`, { method: 'DELETE' });
}

/** Short labels for the tracking an item needs, e.g. ["Lot", "Expiry"]. */
export function trackingLabels(item: Pick<Item, 'lotControlled' | 'serialControlled' | 'expiryControlled'>): string[] {
  const labels: string[] = [];
  if (item.lotControlled) labels.push('Lot');
  if (item.serialControlled) labels.push('Serial');
  if (item.expiryControlled) labels.push('Expiry');
  return labels;
}
