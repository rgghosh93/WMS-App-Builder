import type { ActiveStatus } from './containerTypes';
import { request } from './http';

export interface FacilityRef {
  id: string;
  code: string;
  name: string;
}

export interface Facility extends FacilityRef {
  address: string | null;
  status: ActiveStatus;
  version: number;
}

export interface FacilityInput {
  code: string;
  name: string;
  address: string | null;
  status: ActiveStatus;
  version?: number;
}

export function listFacilities(signal?: AbortSignal): Promise<Facility[]> {
  return request('/api/facilities', { signal });
}

export function saveFacility(id: string | null, input: FacilityInput): Promise<Facility> {
  return request(id ? `/api/facilities/${id}` : '/api/facilities', { method: id ? 'PUT' : 'POST', body: JSON.stringify(input) });
}
