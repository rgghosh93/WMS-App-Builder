import { request, type Page } from './http';
import type { InventoryStatus } from './inventory';
import type { ContainerTypeOption, ItemScan } from './rf';

export type AsnStatus = 'EXPECTED' | 'RECEIVING' | 'RECEIVED' | 'CANCELLED';

export const ASN_STATUSES: { value: AsnStatus; label: string; hint: string }[] = [
  { value: 'EXPECTED', label: 'Expected', hint: 'Announced; nothing has arrived' },
  { value: 'RECEIVING', label: 'Receiving', hint: 'Some of it has been received' },
  { value: 'RECEIVED', label: 'Received', hint: 'Closed: nothing more is coming' },
  { value: 'CANCELLED', label: 'Cancelled', hint: 'Not coming' },
];

export function asnStatusLabel(status: AsnStatus): string {
  return ASN_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export interface AsnSummary {
  id: string;
  asnNumber: string;
  vendorName: string;
  reference: string | null;
  expectedDate: string | null;
  status: AsnStatus;
  lineCount: number;
  expectedQuantity: number;
  receivedQuantity: number;
  createdAt: string;
  closedAt: string | null;
}

/** What a receiver has to know about an item. */
export interface AsnItem {
  id: string;
  itemId: string;
  description: string;
  lotControlled: boolean;
  serialControlled: boolean;
  expiryControlled: boolean;
  shelfLifeDays: number | null;
  scans: ItemScan[];
}

export interface AsnLine {
  id: string;
  lineNumber: number;
  item: AsnItem;
  expectedQuantity: number;
  receivedQuantity: number;
  remaining: number;
  lotNumber: string | null;
  expiryDate: string | null;
  /** The LPN the vendor packed this in, if they said. */
  lpnId: string | null;
}

export interface AsnReceipt {
  id: string;
  lineNumber: number;
  itemId: string;
  description: string;
  quantity: number;
  lotNumber: string | null;
  serialNumber: string | null;
  expiryDate: string | null;
  condition: InventoryStatus;
  lpn: { id: string; lpnId: string; containerType: string };
  location: { id: string; locationId: string };
  receivedBy: string;
  receivedAt: string;
  reversedBy: string | null;
  reversedAt: string | null;
}

export interface AsnDetail extends Omit<AsnSummary, 'lineCount'> {
  lines: AsnLine[];
  receipts: AsnReceipt[];
  events: { occurredAt: string; type: 'CREATED' | 'RECEIVED' | 'REVERSED' | 'CLOSED' | 'CANCELLED'; message: string }[];
  closedBy: string | null;
}

export type AsnSortField = 'createdAt' | 'asnNumber' | 'vendorName' | 'expectedDate' | 'status';

export interface AsnQuery {
  q: string;
  status: AsnStatus | '';
  page: number;
  size: number;
  sort: AsnSortField;
  dir: 'asc' | 'desc';
}

export interface AsnInput {
  asnNumber: string | null;
  vendorName: string;
  reference: string | null;
  expectedDate: string | null;
  lines: { itemId: string; quantity: number; lotNumber: string | null; expiryDate: string | null; lpnId: string | null }[];
}

export function listAsns(query: AsnQuery, signal?: AbortSignal): Promise<Page<AsnSummary>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  params.set('sort', query.sort);
  params.set('dir', query.dir);
  return request(`/api/asns?${params}`, { signal });
}

export function getAsn(id: string, signal?: AbortSignal): Promise<AsnDetail> {
  return request(`/api/asns/${id}`, { signal });
}

export function createAsn(input: AsnInput): Promise<AsnDetail> {
  return request('/api/asns', { method: 'POST', body: JSON.stringify(input) });
}

export function cancelAsn(id: string): Promise<AsnDetail> {
  return request(`/api/asns/${id}/cancel`, { method: 'POST' });
}

// ---------- Receiving on a mobile device ----------

export interface ReceivingLocation {
  id: string;
  locationId: string;
  /** What kind of location it is, in words. */
  type: string;
}

/** What a scanned LPN is. */
export interface LpnCheck {
  lpnId: string;
  kind: 'NEW' | 'ANNOUNCED' | 'OPEN';
  /** The LPN is new, so its container type has to be chosen. */
  needsType: boolean;
  containerType: string | null;
  /** What the vendor said is in it and hasn't been received. */
  expected: AsnLine[];
  /** Why that can't be received in one go, or null if it can. */
  whyNotAll: string | null;
  types: ContainerTypeOption[];
}

export interface ReceiveInput {
  /** Made here for each receipt, so sending it again doesn't receive it twice. */
  requestId: string;
  locationId: string;
  lpnId: string;
  containerTypeId: string | null;
  itemBarcode: string;
  /** In the unit of the scanned barcode. */
  quantity: number;
  lotNumber: string | null;
  expiryDate: string | null;
  serialNumbers: string[];
  condition: InventoryStatus;
  allowOver: boolean;
}

export type ReceiveLpnInput = Pick<ReceiveInput, 'requestId' | 'locationId' | 'lpnId' | 'containerTypeId' | 'condition'>;

const RF = '/api/rf/receiving';

function post<T>(path: string, body: unknown): Promise<T> {
  return request(`${RF}/${path}`, { method: 'POST', body: JSON.stringify(body) });
}

export function listReceivingLocations(signal?: AbortSignal): Promise<ReceivingLocation[]> {
  return request(`${RF}/locations`, { signal });
}

export function getReceivingLocation(code: string): Promise<ReceivingLocation> {
  return request(`${RF}/locations/${encodeURIComponent(code)}`);
}

export function listOpenAsns(signal?: AbortSignal): Promise<AsnSummary[]> {
  return request(`${RF}/asns`, { signal });
}

export function getOpenAsn(number: string): Promise<AsnDetail> {
  return request(`${RF}/asns/${encodeURIComponent(number)}`);
}

/** A blank LPN makes a new LPN number. */
export function checkLpn(asnId: string, locationId: string, lpnId: string): Promise<LpnCheck> {
  return post(`asns/${asnId}/lpn`, { locationId, lpnId });
}

export function receiveItems(asnId: string, input: ReceiveInput): Promise<AsnDetail> {
  return post(`asns/${asnId}/receive`, input);
}

export function receiveWholeLpn(asnId: string, input: ReceiveLpnInput): Promise<AsnDetail> {
  return post(`asns/${asnId}/receive-lpn`, input);
}

export function reverseReceipt(receiptId: string): Promise<AsnDetail> {
  return post(`receipts/${receiptId}/reverse`, {});
}

export function closeAsn(asnId: string, acceptShort: boolean): Promise<AsnDetail> {
  return post(`asns/${asnId}/close`, { acceptShort });
}
