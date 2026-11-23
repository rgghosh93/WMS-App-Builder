import { request, type Page } from './http';
import type { Carrier, CarrierMode } from './carriers';
import type { OrderRef } from './orders';
import type { Carton } from './packing';

export type ShipmentStatus = 'OPEN' | 'LOADING' | 'SHIPPED';
export type ManifestStatus = 'OPEN' | 'CLOSED';

export const SHIPMENT_STATUSES: { value: ShipmentStatus; label: string }[] = [
  { value: 'OPEN', label: 'Open' },
  { value: 'LOADING', label: 'Loading' },
  { value: 'SHIPPED', label: 'Shipped' },
];

export function shipmentStatusLabel(status: ShipmentStatus): string {
  return SHIPMENT_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** CSS badge for a shipment or manifest status. */
export function shippingBadge(status: ShipmentStatus | ManifestStatus): string {
  return { OPEN: 'badge--task-open', LOADING: 'badge--task-in-progress', SHIPPED: 'badge--task-completed', CLOSED: 'badge--task-completed' }[status];
}

/** Where an order goes. */
export interface ShipTo {
  name: string;
  line1: string;
  line2: string | null;
  city: string;
  state: string | null;
  postalCode: string;
  country: string;
}

/** The address as it is written on a label. */
export function addressLines(a: ShipTo): string[] {
  return [a.name, a.line1, a.line2 ?? '', `${a.city}${a.state ? `, ${a.state}` : ''} ${a.postalCode}`, a.country].filter(Boolean);
}

export interface CarrierRef {
  id: string;
  carrierCode: string;
  name: string;
  mode: CarrierMode;
  trackingRequired: boolean;
}

/** Closed cartons of one order going out together. */
export interface Shipment {
  id: string;
  shipmentNumber: string;
  status: ShipmentStatus;
  order: OrderRef;
  shipTo: ShipTo | null;
  carrier: CarrierRef | null;
  service: { id: string; serviceCode: string; name: string } | null;
  manifest: { id: string; manifestNumber: string } | null;
  loadedCount: number;
  totalQuantity: number;
  /** Eaches of the order that are not packed, so won't go with this shipment. */
  unpackedQuantity: number;
  /** What still has to be filled in before it can be loaded; empty when ready. */
  missing: string[];
  cartons: Carton[];
  createdAt: string;
  shippedAt: string | null;
  version: number;
}

/** What leaves with one carrier from one dock door. */
export interface Manifest {
  id: string;
  manifestNumber: string;
  status: ManifestStatus;
  carrier: CarrierRef;
  door: { id: string; locationId: string };
  trailerNumber: string | null;
  sealNumber: string | null;
  cartonCount: number;
  totalQuantity: number;
  shipments: Shipment[];
  createdBy: string;
  createdAt: string;
  closedBy: string | null;
  closedAt: string | null;
}

// ---------- Shipments ----------

export interface ShipmentQuery {
  q: string;
  status: ShipmentStatus | '';
  carrierId: string;
  page: number;
  size: number;
}

export function listShipments(query: ShipmentQuery, signal?: AbortSignal): Promise<Page<Shipment>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  if (query.carrierId) params.set('carrierId', query.carrierId);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/shipments?${params}`, { signal });
}

export function getShipment(id: string, signal?: AbortSignal): Promise<Shipment> {
  return request(`/api/shipments/${id}`, { signal });
}

export function setShipmentService(id: string, carrierServiceId: string | null, version: number): Promise<Shipment> {
  return request(`/api/shipments/${id}/service`, { method: 'PUT', body: JSON.stringify({ carrierServiceId, version }) });
}

export function setTracking(shipmentId: string, cartonId: string, trackingNumber: string): Promise<Shipment> {
  return request(`/api/shipments/${shipmentId}/cartons/${cartonId}/tracking`, {
    method: 'PUT',
    body: JSON.stringify({ trackingNumber }),
  });
}

export function setShipTo(orderId: string, shipTo: ShipTo): Promise<ShipTo> {
  return request(`/api/orders/${orderId}/ship-to`, { method: 'PUT', body: JSON.stringify(shipTo) });
}

// ---------- Manifests ----------

export interface ManifestQuery {
  q: string;
  status: ManifestStatus | '';
  page: number;
  size: number;
}

export function listManifests(query: ManifestQuery, signal?: AbortSignal): Promise<Page<Manifest>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/manifests?${params}`, { signal });
}

export function getManifest(id: string, signal?: AbortSignal): Promise<Manifest> {
  return request(`/api/manifests/${id}`, { signal });
}

// ---------- Loading on a device (signed in) ----------

export interface DockDoor {
  id: string;
  locationId: string;
  /** What is being loaded there, if anything. */
  manifestNumber: string | null;
  carrierName: string | null;
}

export interface DoorState {
  door: { id: string; locationId: string };
  manifest: Manifest | null;
  /** Shipments for the manifest's carrier that are not loaded yet. */
  waiting: Shipment[];
}

const LOADING = '/api/rf/loading';

function post<T>(path: string, body: unknown = {}): Promise<T> {
  return request(`${LOADING}/${path}`, { method: 'POST', body: JSON.stringify(body) });
}

export function listDoors(signal?: AbortSignal): Promise<DockDoor[]> {
  return request(`${LOADING}/doors`, { signal });
}

export function listLoadingCarriers(signal?: AbortSignal): Promise<Carrier[]> {
  return request(`${LOADING}/carriers`, { signal });
}

export function getDoor(locationId: string, signal?: AbortSignal): Promise<DoorState> {
  return request(`${LOADING}/doors/${encodeURIComponent(locationId)}`, { signal });
}

export function openManifest(locationId: string, carrierId: string): Promise<DoorState> {
  return post(`doors/${encodeURIComponent(locationId)}/manifest`, { carrierId });
}

export function loadCarton(manifestId: string, lpnId: string): Promise<DoorState> {
  return post(`manifests/${manifestId}/load`, { lpnId });
}

export function unloadCarton(manifestId: string, lpnId: string): Promise<DoorState> {
  return post(`manifests/${manifestId}/unload`, { lpnId });
}

export function discardManifest(manifestId: string): Promise<DoorState> {
  return post(`manifests/${manifestId}/discard`);
}

/** Ship confirm. With leaveBehind, cartons that aren't loaded move to a shipment of their own. */
export function closeManifest(
  manifestId: string,
  details: { trailerNumber: string; sealNumber: string; leaveBehind: boolean },
): Promise<Manifest> {
  return post(`manifests/${manifestId}/close`, details);
}
