import { request } from './http';
import type { ActiveStatus } from './containerTypes';

export type CarrierMode = 'PARCEL' | 'LTL' | 'TRUCKLOAD' | 'OTHER';

export const CARRIER_MODES: { value: CarrierMode; label: string }[] = [
  { value: 'PARCEL', label: 'Parcel' },
  { value: 'LTL', label: 'LTL freight' },
  { value: 'TRUCKLOAD', label: 'Truckload' },
  { value: 'OTHER', label: 'Other' },
];

export function carrierModeLabel(mode: CarrierMode): string {
  return CARRIER_MODES.find((m) => m.value === mode)?.label ?? mode;
}

export interface CarrierService {
  id: string;
  serviceCode: string;
  name: string;
  status: ActiveStatus;
}

export interface Carrier {
  id: string;
  carrierCode: string;
  name: string;
  mode: CarrierMode;
  /** Every carton needs a tracking number before it can be loaded. */
  trackingRequired: boolean;
  status: ActiveStatus;
  services: CarrierService[];
  version: number;
}

export interface CarrierInput {
  carrierCode: string;
  name: string;
  mode: CarrierMode;
  trackingRequired: boolean;
  status: ActiveStatus;
  services: { id: string | null; serviceCode: string; name: string; status: ActiveStatus }[];
  version?: number;
}

const BASE = '/api/carriers';

export function listCarriers(signal?: AbortSignal): Promise<Carrier[]> {
  return request(BASE, { signal });
}

export function createCarrier(input: CarrierInput): Promise<Carrier> {
  return request(BASE, { method: 'POST', body: JSON.stringify(input) });
}

export function updateCarrier(id: string, input: CarrierInput): Promise<Carrier> {
  return request(`${BASE}/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteCarrier(id: string): Promise<void> {
  return request(`${BASE}/${id}`, { method: 'DELETE' });
}
