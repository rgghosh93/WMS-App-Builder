import { request, type Page } from './http';
import type { ContainerCategory } from './containerTypes';
import type { ContainerStatus } from './containers';

export type InventoryStatus = 'AVAILABLE' | 'ON_HOLD' | 'DAMAGED';

export const INVENTORY_STATUSES: { value: InventoryStatus; label: string }[] = [
  { value: 'AVAILABLE', label: 'Available' },
  { value: 'ON_HOLD', label: 'On hold' },
  { value: 'DAMAGED', label: 'Damaged' },
];

export function inventoryStatusLabel(status: InventoryStatus): string {
  return INVENTORY_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export interface LocationRef {
  id: string;
  locationId: string;
  type: string;
  area: string | null;
  zone: string | null;
}

export interface LocationSummary {
  location: LocationRef;
  /** Eaches in the location, loose and inside containers. */
  totalQuantity: number;
  itemCount: number;
  /** LPNs in the location, including ones inside other LPNs. */
  containerCount: number;
  looseLineCount: number;
}

export interface Pack {
  uomCode: string;
  quantity: number;
}

export interface InventoryLine {
  id: string;
  item: { id: string; itemId: string; description: string; packs: Pack[] };
  quantity: number;
  /** Eaches of this stock reserved for orders. */
  allocatedQuantity: number;
  lotNumber: string | null;
  serialNumber: string | null;
  expiryDate: string | null;
  status: InventoryStatus;
  receivedAt: string;
}

export interface ContainerNode {
  id: string;
  lpnId: string;
  permanent: boolean;
  containerType: { id: string; containerTypeId: string; description: string; category: ContainerCategory };
  status: ContainerStatus;
  /** Eaches in this container and everything inside it. */
  totalQuantity: number;
  itemCount: number;
  lines: InventoryLine[];
  children: ContainerNode[];
}

export interface LocationDetail {
  location: LocationRef;
  totalQuantity: number;
  itemCount: number;
  containerCount: number;
  lines: InventoryLine[];
  containers: ContainerNode[];
}

export interface LpnRef {
  id: string;
  lpnId: string;
}

export interface ContainerSummary {
  id: string;
  lpnId: string;
  permanent: boolean;
  containerType: { id: string; containerTypeId: string; description: string; category: ContainerCategory };
  status: ContainerStatus;
  /** Its own location, or that of the outermost container it is inside; null if unknown. */
  location: LocationRef | null;
  /** Containers it sits inside, outermost first. */
  path: LpnRef[];
  /** Eaches in it and everything inside it. */
  totalQuantity: number;
  itemCount: number;
  /** Containers inside it, at any depth. */
  nestedCount: number;
  /** Inventory records held directly in it. */
  lineCount: number;
}

export interface ContainerDetail {
  location: LocationRef | null;
  path: LpnRef[];
  container: ContainerNode;
}

export type ContainerKind = '' | 'permanent' | 'inventory';

export interface InventoryContainerQuery {
  q: string;
  category: ContainerCategory | '';
  kind: ContainerKind;
  page: number;
  size: number;
}

export function listInventoryContainers(query: InventoryContainerQuery, signal?: AbortSignal): Promise<Page<ContainerSummary>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.category) params.set('category', query.category);
  if (query.kind) params.set('permanent', String(query.kind === 'permanent'));
  params.set('withStock', 'true');
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/inventory/containers?${params}`, { signal });
}

export function getContainerInventory(containerId: string, signal?: AbortSignal): Promise<ContainerDetail> {
  return request(`/api/inventory/containers/${containerId}`, { signal });
}

export interface InventoryLocationQuery {
  q: string;
  occupied: boolean;
  page: number;
  size: number;
}

export function listInventoryLocations(query: InventoryLocationQuery, signal?: AbortSignal): Promise<Page<LocationSummary>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  params.set('occupied', String(query.occupied));
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/inventory/locations?${params}`, { signal });
}

export function getLocationInventory(locationId: string, signal?: AbortSignal): Promise<LocationDetail> {
  return request(`/api/inventory/locations/${locationId}`, { signal });
}

/**
 * Splits a quantity in eaches into the item's packaging, largest first:
 * 27 EA with CS = 6 → "4 CS + 3 EA". Returns "" when no pack fits.
 */
export function packBreakdown(quantity: number, packs: Pack[], baseCode: string): string {
  const sorted = [...packs].filter((p) => p.quantity > 1).sort((a, b) => b.quantity - a.quantity);
  if (sorted.length === 0 || quantity < sorted[sorted.length - 1].quantity) return '';
  const parts: string[] = [];
  let left = quantity;
  for (const pack of sorted) {
    const count = Math.floor(left / pack.quantity);
    if (count > 0) {
      parts.push(`${count.toLocaleString()} ${pack.uomCode}`);
      left -= count * pack.quantity;
    }
  }
  if (left > 0) parts.push(`${left.toLocaleString()} ${baseCode}`);
  return parts.join(' + ');
}

/** Counts every container in a tree, at any depth. */
export function countContainers(nodes: ContainerNode[]): number {
  return nodes.reduce((sum, n) => sum + 1 + countContainers(n.children), 0);
}

/** Ids of every container in a tree, at any depth. */
export function containerIds(nodes: ContainerNode[]): string[] {
  return nodes.flatMap((n) => [n.id, ...containerIds(n.children)]);
}
