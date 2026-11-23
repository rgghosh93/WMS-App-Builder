import type { ActiveStatus } from './containerTypes';
import { request, type Page } from './http';
import type { InventoryStatus } from './inventory';
import type { ItemScan } from './rf';

export interface ControlItem {
  id: string;
  itemId: string;
  description: string;
  lotControlled: boolean;
  serialControlled: boolean;
  expiryControlled: boolean;
}

/** One inventory record. */
export interface Stock {
  id: string;
  item: ControlItem;
  quantity: number;
  reserved: number;
  lotNumber: string | null;
  serialNumber: string | null;
  expiryDate: string | null;
  status: InventoryStatus;
  holdReason: string | null;
  heldAt: string | null;
  expired: boolean;
  locationId: string | null;
  /** Null while someone is carrying it. */
  location: string | null;
  locationType: string | null;
  containerId: string | null;
  lpn: string | null;
  receivedAt: string;
}

export interface OnHand {
  lotNumber: string | null;
  expiryDate: string | null;
  total: number;
  /** In storage, good and not reserved: what an order could get now. */
  available: number;
  reserved: number;
  onHold: number;
  damaged: number;
  expired: number;
  /** Good but not in storage. */
  elsewhere: number;
}

export interface ItemOnHand {
  item: ControlItem;
  total: OnHand;
  lots: OnHand[];
}

export type TransactionType =
  | 'OPENING'
  | 'RECEIVE'
  | 'RECEIVE_UNDO'
  | 'PUTAWAY'
  | 'MOVE'
  | 'REPLENISH'
  | 'PICK'
  | 'PACK'
  | 'UNPACK'
  | 'SHIP'
  | 'HOLD'
  | 'RELEASE'
  | 'ADJUST'
  | 'COUNT';

export const TRANSACTION_TYPES: { value: TransactionType; label: string }[] = [
  { value: 'OPENING', label: 'Opening stock' },
  { value: 'RECEIVE', label: 'Received' },
  { value: 'RECEIVE_UNDO', label: 'Receipt undone' },
  { value: 'PUTAWAY', label: 'Put away' },
  { value: 'MOVE', label: 'Moved' },
  { value: 'REPLENISH', label: 'Replenished' },
  { value: 'PICK', label: 'Picked' },
  { value: 'PACK', label: 'Packed' },
  { value: 'UNPACK', label: 'Unpacked' },
  { value: 'SHIP', label: 'Shipped' },
  { value: 'HOLD', label: 'Held' },
  { value: 'RELEASE', label: 'Released' },
  { value: 'ADJUST', label: 'Adjusted' },
  { value: 'COUNT', label: 'Counted' },
];

export function transactionLabel(type: TransactionType): string {
  return TRANSACTION_TYPES.find((t) => t.value === type)?.label ?? type;
}

export interface Transaction {
  id: string;
  number: number;
  occurredAt: string;
  type: TransactionType;
  itemId: string;
  itemCode: string;
  lotNumber: string | null;
  serialNumber: string | null;
  expiryDate: string | null;
  quantity: number;
  fromLocation: string | null;
  fromLpn: string | null;
  toLocation: string | null;
  toLpn: string | null;
  fromStatus: InventoryStatus | null;
  toStatus: InventoryStatus | null;
  reference: string | null;
  reason: string | null;
  userName: string;
}

const API = '/api/inventory';

function list<T>(path: string) {
  return (params: URLSearchParams, signal?: AbortSignal): Promise<Page<T>> => request(`${API}/${path}?${params}`, { signal });
}

function post<T>(path: string, body: unknown = {}): Promise<T> {
  return request(`${API}/${path}`, { method: 'POST', body: JSON.stringify(body) });
}

export const listStock = list<Stock>('stock');
export const listOnHand = list<ItemOnHand>('on-hand');
export const listHistory = list<Transaction>('history');

// ---------- Reasons and the limit ----------

export type ReasonKind = 'ADJUSTMENT' | 'HOLD';

export interface Reason {
  id: string;
  kind: ReasonKind;
  code: string;
  name: string;
  status: ActiveStatus;
  version: number;
}

export type ReasonInput = Omit<Reason, 'id' | 'version'> & { version?: number };

export function listReasons(signal?: AbortSignal): Promise<Reason[]> {
  return request(`${API}/reasons`, { signal });
}

export function createReason(input: ReasonInput): Promise<Reason> {
  return post('reasons', input);
}

export function updateReason(id: string, input: ReasonInput): Promise<Reason> {
  return request(`${API}/reasons/${id}`, { method: 'PUT', body: JSON.stringify(input) });
}

export function deleteReason(id: string): Promise<void> {
  return request(`${API}/reasons/${id}`, { method: 'DELETE' });
}

/** Changes of more eaches than the limit need a second person. */
export function getInventorySettings(signal?: AbortSignal): Promise<{ approvalLimit: number }> {
  return request(`${API}/settings`, { signal });
}

export function saveInventorySettings(approvalLimit: number): Promise<{ approvalLimit: number }> {
  return request(`${API}/settings`, { method: 'PUT', body: JSON.stringify({ approvalLimit }) });
}

// ---------- Holds ----------

/** What to hold: one of an LPN, a location, an item, or a lot of an item. */
export interface HoldScope {
  lpnId?: string;
  locationId?: string;
  itemId?: string;
  lotNumber?: string;
}

export interface HoldResult {
  records: number;
  quantity: number;
  ordersReallocated: number;
  /** Reserved for picks under way or done: it stays with its order but goes no further. */
  stuck: number;
}

export function previewHold(scope: HoldScope): Promise<HoldResult> {
  return post('holds/preview', scope);
}

export function placeHold(input: { scope: HoldScope; damaged: boolean; reasonId: string; note: string }): Promise<HoldResult> {
  return post('holds', input);
}

export function releaseHolds(inventoryIds: string[], reasonId: string, note: string): Promise<HoldResult> {
  return post('holds/release', { inventoryIds, reasonId, note });
}

// ---------- Adjustments ----------

export type AdjustmentStatus = 'PENDING' | 'APPLIED' | 'REJECTED';

export const ADJUSTMENT_STATUSES: { value: AdjustmentStatus; label: string }[] = [
  { value: 'PENDING', label: 'To approve' },
  { value: 'APPLIED', label: 'Made' },
  { value: 'REJECTED', label: 'Rejected' },
];

export interface Adjustment {
  id: string;
  number: string;
  status: AdjustmentStatus;
  item: ControlItem;
  place: string;
  lotNumber: string | null;
  serialNumber: string | null;
  expiryDate: string | null;
  stockStatus: InventoryStatus;
  change: number;
  reason: string;
  requester: string;
  requestedBy: string;
  requestedAt: string;
  decider: string | null;
  decidedAt: string | null;
}

export const listAdjustments = list<Adjustment>('adjustments');

export function changeQuantity(input: { inventoryId: string; expectedQuantity: number; newQuantity: number; reasonId: string; note: string }): Promise<Adjustment> {
  return post('adjustments/change', input);
}

export interface AddStockInput {
  itemId: string;
  locationId: string | null;
  lpnId: string | null;
  quantity: number;
  lotNumber: string | null;
  expiryDate: string | null;
  serialNumber: string | null;
  status: InventoryStatus;
  reasonId: string;
  note: string;
}

export function addStock(input: AddStockInput): Promise<Adjustment> {
  return post('adjustments/add', input);
}

export function decideAdjustment(id: string, approve: boolean): Promise<Adjustment> {
  return post(`adjustments/${id}/${approve ? 'approve' : 'reject'}`);
}

// ---------- Counts ----------

export type CountStatus = 'OPEN' | 'COUNTING' | 'REVIEW' | 'RECOUNT' | 'COMPLETED' | 'CANCELLED';

export const COUNT_STATUSES: { value: CountStatus; label: string }[] = [
  { value: 'OPEN', label: 'Planned' },
  { value: 'COUNTING', label: 'Counting' },
  { value: 'REVIEW', label: 'To review' },
  { value: 'RECOUNT', label: 'To recount' },
  { value: 'COMPLETED', label: 'Counted' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

export function countStatusLabel(status: CountStatus): string {
  return COUNT_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export interface Count {
  id: string;
  number: string;
  status: CountStatus;
  locationId: string;
  location: string;
  plannedBy: string;
  plannedAt: string;
  counter: string | null;
  counterId: string | null;
  countedAt: string | null;
  decider: string | null;
  decidedAt: string | null;
  rounds: number;
  expected: number;
  counted: number;
}

export interface CountDetail {
  task: Count;
  results: { lpn: string | null; item: ControlItem; lotNumber: string | null; serialNumber: string | null; expected: number; counted: number; difference: number }[];
}

export interface CountPlan {
  locationId?: string;
  area?: string;
  zone?: string;
  /** A counting zone. */
  zoneId?: string;
  itemId?: string;
  includeEmpty?: boolean;
}

export const listCounts = list<Count>('counts');

export function getCount(id: string, signal?: AbortSignal): Promise<CountDetail> {
  return request(`${API}/counts/${id}`, { signal });
}

export function planCounts(plan: CountPlan): Promise<{ created: number; skipped: number }> {
  return post('counts', plan);
}

export function decideCount(id: string, what: 'approve' | 'recount' | 'cancel'): Promise<CountDetail> {
  return post(`counts/${id}/${what}`);
}

// ---------- Moving on a device ----------

export interface MovableLine {
  inventoryId: string;
  item: ControlItem;
  scans: ItemScan[];
  lotNumber: string | null;
  serialNumber: string | null;
  expiryDate: string | null;
  status: InventoryStatus;
  quantity: number;
  /** Not reserved for orders. */
  free: number;
}

export interface MoveSource {
  /** Set for an LPN, which is moved whole. */
  lpn: string | null;
  containerType: string | null;
  location: string;
  lines: MovableLine[];
}

function rf<T>(path: string, body?: unknown, method = 'POST'): Promise<T> {
  return request(`/api/rf/${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body) });
}

export function getMoveSource(code: string): Promise<MoveSource> {
  return request(`/api/rf/moves/from/${encodeURIComponent(code)}`);
}

export function moveLpn(lpnId: string, toLocationId: string, allowOver: boolean): Promise<{ message: string }> {
  return rf('moves/lpn', { lpnId, toLocationId, allowOver });
}

export function moveLoose(input: { requestId: string; inventoryId: string; quantity: number; toLocationId: string; allowOver: boolean }): Promise<{ message: string }> {
  return rf('moves/loose', input);
}

// ---------- Counting on a device ----------

export interface CountEntry {
  id: string;
  lpn: string | null;
  item: ControlItem;
  lotNumber: string | null;
  serialNumber: string | null;
  quantity: number;
}

export interface CountState {
  counting: { id: string; number: string; location: string; entries: CountEntry[] } | null;
  waitingCount: number;
  waiting: { number: string; location: string; again: boolean }[];
  message: string | null;
}

export interface CountEntryInput {
  requestId: string;
  lpnId: string | null;
  itemBarcode: string;
  quantity: number | null;
  lotNumber: string | null;
  serialNumbers: string[];
}

export function getCounting(signal?: AbortSignal): Promise<CountState> {
  return request('/api/rf/counts', { signal });
}

export function startCount(locationId: string, takeOver: boolean): Promise<CountState> {
  return rf('counts/start', { locationId, takeOver });
}

export function getCountItem(code: string): Promise<{ item: ControlItem; scan: ItemScan }> {
  return request(`/api/rf/counts/items/${encodeURIComponent(code)}`);
}

export function enterCount(id: string, entry: CountEntryInput): Promise<CountState> {
  return rf(`counts/${id}/entries`, entry);
}

export function removeCountEntry(id: string, entryId: string): Promise<CountState> {
  return rf(`counts/${id}/entries/${entryId}`, undefined, 'DELETE');
}

export function finishCount(id: string): Promise<CountState> {
  return rf(`counts/${id}/finish`, {});
}

export function giveBackCount(id: string): Promise<CountState> {
  return rf(`counts/${id}/give-back`, {});
}
