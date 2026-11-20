import { request, type Page } from './http';
import type { InventoryStatus } from './inventory';

export type OrderStatus = 'NEW' | 'PARTIALLY_ALLOCATED' | 'ALLOCATED' | 'BACKORDERED' | 'CANCELLED';
export type LineStatus = OrderStatus;
export type PickTaskStatus = 'OPEN' | 'CANCELLED';
/** DISCRETE picks for one order; BATCH picks for several orders in one walk. */
export type PickMethod = 'DISCRETE' | 'BATCH';

export function pickMethodLabel(method: PickMethod): string {
  return method === 'BATCH' ? 'Batch' : 'Discrete';
}
export type OrderEventType = 'RECEIVED' | 'ALLOCATED' | 'SHORT' | 'TASKS_CREATED' | 'CANCELLED';

export const ORDER_STATUSES: { value: OrderStatus; label: string; hint: string }[] = [
  { value: 'NEW', label: 'New', hint: 'Received; not allocated yet' },
  { value: 'PARTIALLY_ALLOCATED', label: 'Partially allocated', hint: 'Some of the ordered quantity has stock reserved' },
  { value: 'ALLOCATED', label: 'Allocated', hint: 'Fully allocated, with pick tasks' },
  { value: 'BACKORDERED', label: 'Backordered', hint: 'No stock could be reserved' },
  { value: 'CANCELLED', label: 'Cancelled', hint: 'Stock released and tasks cancelled' },
];

export function orderStatusLabel(status: OrderStatus): string {
  return ORDER_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export function taskStatusLabel(status: PickTaskStatus): string {
  return status === 'OPEN' ? 'Open' : 'Cancelled';
}

/** CSS modifier for a status badge, e.g. "partially-allocated". */
export function statusClass(status: string): string {
  return status.toLowerCase().replace(/_/g, '-');
}

export interface ItemRef {
  id: string;
  itemId: string;
  description: string;
}

export interface Ref {
  id: string;
  locationId?: string;
  lpnId?: string;
  orderNumber?: string;
}

export interface OrderSummary {
  id: string;
  orderNumber: string;
  customerName: string;
  requestedShipDate: string | null;
  priority: number;
  status: OrderStatus;
  lineCount: number;
  orderedQuantity: number;
  allocatedQuantity: number;
  openTaskCount: number;
  createdAt: string;
}

export interface OrderLineView {
  id: string;
  lineNumber: number;
  item: ItemRef;
  orderedQuantity: number;
  allocatedQuantity: number;
  requestedLot: string | null;
  status: LineStatus;
}

export interface OrderRef {
  id: string;
  orderNumber: string;
  customerName: string;
  status: OrderStatus;
}

export interface AllocationView {
  id: string;
  order: OrderRef;
  lineNumber: number;
  item: ItemRef;
  quantity: number;
  location: { id: string; locationId: string } | null;
  container: { id: string; lpnId: string } | null;
  lotNumber: string | null;
  serialNumber: string | null;
  expiryDate: string | null;
  inventoryStatus: InventoryStatus;
  /** The pick task detail made from this allocation. */
  task: TaskRef | null;
  createdAt: string;
}

/** Points at one detail of a pick task. */
export interface TaskRef {
  id: string;
  taskNumber: string;
  detailNumber: number;
  status: PickTaskStatus;
}

/** One pick of a task: made from exactly one allocation. */
export interface PickTaskDetailView {
  id: string;
  detailNumber: number;
  /** The order this pick is for; a batch task picks for several. */
  order: OrderRef;
  lineNumber: number;
  item: ItemRef;
  quantity: number;
  fromLocation: { id: string; locationId: string };
  fromContainer: { id: string; lpnId: string } | null;
  lotNumber: string | null;
  serialNumber: string | null;
  status: PickTaskStatus;
  /** The allocation this detail picks; null once cancelled. */
  allocationId: string | null;
}

/** A pick task, made of one detail per allocation, for one order (discrete) or several (batch). */
export interface PickTaskView {
  id: string;
  taskNumber: string;
  pickMethod: PickMethod;
  /** The orders it picks for, in detail order. */
  orders: OrderRef[];
  status: PickTaskStatus;
  detailCount: number;
  lineCount: number;
  locationCount: number;
  totalQuantity: number;
  details: PickTaskDetailView[];
  createdAt: string;
}

export interface OrderEventView {
  occurredAt: string;
  type: OrderEventType;
  message: string;
}

export interface OrderDetail {
  id: string;
  orderNumber: string;
  customerName: string;
  requestedShipDate: string | null;
  priority: number;
  status: OrderStatus;
  lines: OrderLineView[];
  allocations: AllocationView[];
  tasks: PickTaskView[];
  events: OrderEventView[];
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface OrderInput {
  orderNumber: string | null;
  customerName: string;
  requestedShipDate: string | null;
  priority: number;
  lines: { itemId: string; quantity: number; requestedLot: string | null }[];
}

export type OrderSortField = 'createdAt' | 'orderNumber' | 'customerName' | 'requestedShipDate' | 'priority' | 'status';

export interface OrderQuery {
  q: string;
  status: OrderStatus | '';
  page: number;
  size: number;
  sort: OrderSortField;
  dir: 'asc' | 'desc';
}

export function listOrders(query: OrderQuery, signal?: AbortSignal): Promise<Page<OrderSummary>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  params.set('sort', query.sort);
  params.set('dir', query.dir);
  return request(`/api/orders?${params}`, { signal });
}

export function getOrder(id: string, signal?: AbortSignal): Promise<OrderDetail> {
  return request(`/api/orders/${id}`, { signal });
}

/** Receives an order; with allocate false it stays New, e.g. to be batched with others. */
export function createOrder(input: OrderInput, allocate = true): Promise<OrderDetail> {
  return request(`/api/orders${allocate ? '' : '?allocate=false'}`, { method: 'POST', body: JSON.stringify(input) });
}

export interface BatchAllocation {
  /** The batch task, or null if no stock could be reserved for any of the orders. */
  task: PickTaskView | null;
  orders: OrderRef[];
}

/** Allocates several orders onto one batch pick task. */
export function batchAllocate(orderIds: string[]): Promise<BatchAllocation> {
  return request('/api/orders/batch-allocate', { method: 'POST', body: JSON.stringify({ orderIds }) });
}

/** Orders that still need stock and so can be allocated (alone or in a batch). */
export function canAllocate(status: OrderStatus): boolean {
  return status === 'NEW' || status === 'PARTIALLY_ALLOCATED' || status === 'BACKORDERED';
}

export function allocateOrder(id: string): Promise<OrderDetail> {
  return request(`/api/orders/${id}/allocate`, { method: 'POST' });
}

export function cancelOrder(id: string): Promise<OrderDetail> {
  return request(`/api/orders/${id}/cancel`, { method: 'POST' });
}

export interface AllocationQuery {
  q: string;
  orderStatus: OrderStatus | '';
  inventoryStatus: InventoryStatus | '';
  page: number;
  size: number;
}

export interface AllocationList extends Page<AllocationView> {
  /** Eaches reserved across every allocation, not just this page. */
  totalAllocatedQuantity: number;
}

export function listAllocations(query: AllocationQuery, signal?: AbortSignal): Promise<AllocationList> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.orderStatus) params.set('orderStatus', query.orderStatus);
  if (query.inventoryStatus) params.set('inventoryStatus', query.inventoryStatus);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/allocations?${params}`, { signal });
}

export interface PickTaskQuery {
  q: string;
  status: PickTaskStatus | '';
  pickMethod: PickMethod | '';
  page: number;
  size: number;
}

export function listPickTasks(query: PickTaskQuery, signal?: AbortSignal): Promise<Page<PickTaskView>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  if (query.pickMethod) params.set('pickMethod', query.pickMethod);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/pick-tasks?${params}`, { signal });
}

export function getPickTask(id: string, signal?: AbortSignal): Promise<PickTaskView> {
  return request(`/api/pick-tasks/${id}`, { signal });
}
