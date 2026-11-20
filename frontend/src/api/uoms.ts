import { request } from './http';
import type { ActiveStatus } from './containerTypes';

export type UomKind = 'EACH' | 'INNER_PACK' | 'CASE' | 'LAYER' | 'PALLET' | 'OTHER';

/** Kinds a user can pick; "Each" is reserved for the base unit. */
export const UOM_KINDS: { value: UomKind; label: string }[] = [
  { value: 'INNER_PACK', label: 'Inner pack' },
  { value: 'CASE', label: 'Case' },
  { value: 'LAYER', label: 'Layer' },
  { value: 'PALLET', label: 'Pallet' },
  { value: 'OTHER', label: 'Other' },
];

export function uomKindLabel(kind: UomKind): string {
  return kind === 'EACH' ? 'Each' : (UOM_KINDS.find((k) => k.value === kind)?.label ?? kind);
}

export interface Uom {
  id: string;
  uomCode: string;
  description: string;
  kind: UomKind;
  status: ActiveStatus;
  base: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface UomInput {
  uomCode: string;
  description: string;
  kind: UomKind;
  status: ActiveStatus;
  version?: number;
}

const BASE = '/api/uoms';

/** All units of measure, base unit first. */
export function listUoms(signal?: AbortSignal): Promise<Uom[]> {
  return request(BASE, { signal });
}

export function createUom(input: UomInput): Promise<Uom> {
  return request(BASE, { method: 'POST', body: JSON.stringify(input) });
}

export function updateUom(id: string, input: UomInput): Promise<Uom> {
  return request(`${BASE}/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteUom(id: string): Promise<void> {
  return request(`${BASE}/${id}`, { method: 'DELETE' });
}
