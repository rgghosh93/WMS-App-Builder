import { request, type Page } from './http';
import type { InventoryStatus } from './inventory';
import type { PackTask } from './packing';
import type { ShipTo, Shipment } from './shipping';

export type OrderStatus = 'NEW' | 'PARTIALLY_ALLOCATED' | 'ALLOCATED' | 'PICKING' | 'PICKED' | 'PACKING' | 'PACKED' | 'PARTIALLY_SHIPPED' | 'SHIPPED' | 'BACKORDERED' | 'CANCELLED';
export type LineStatus = OrderStatus;
export type PickTaskStatus = 'OPEN' | 'IN_PROGRESS' | 'PICKED' | 'COMPLETED' | 'CANCELLED';
export type PickDetailStatus = 'OPEN' | 'PICKED' | 'SHORT' | 'CANCELLED';
/** DISCRETE picks for one order; BATCH picks for several orders in one walk. */
export type PickMethod = 'DISCRETE' | 'BATCH';

export function pickMethodLabel(method: PickMethod): string {
  return method === 'BATCH' ? 'Batch' : 'Discrete';
}
export type OrderEventType = 'RECEIVED' | 'ALLOCATED' | 'SHORT' | 'TASKS_CREATED' | 'PICKED' | 'DROPPED' | 'PACKED' | 'SHIPPED' | 'CLOSED_SHORT' | 'CANCELLED';

export const ORDER_STATUSES: { value: OrderStatus; label: string; hint: string }[] = [
  { value: 'NEW', label: 'New', hint: 'Received; not allocated yet' },
  { value: 'PARTIALLY_ALLOCATED', label: 'Partially allocated', hint: 'Some of the ordered quantity has stock reserved' },
  { value: 'ALLOCATED', label: 'Allocated', hint: 'Fully allocated, with pick tasks' },
  { value: 'PICKING', label: 'Picking', hint: 'Picking has started; some of it is in outbound containers' },
  { value: 'PICKED', label: 'Picked', hint: 'Everything ordered is picked' },
  { value: 'PACKING', label: 'Packing', hint: 'Packing has started; some of it is sealed in cartons' },
  { value: 'PACKED', label: 'Packed', hint: 'Everything ordered is packed and ready to ship' },
  { value: 'PARTIALLY_SHIPPED', label: 'Partially shipped', hint: 'Some of it has shipped; the rest has not' },
  { value: 'SHIPPED', label: 'Shipped', hint: 'Everything ordered has shipped' },
  { value: 'BACKORDERED', label: 'Backordered', hint: 'No stock could be reserved' },
  { value: 'CANCELLED', label: 'Cancelled', hint: 'Stock released and tasks cancelled' },
];

export function orderStatusLabel(status: OrderStatus): string {
  return ORDER_STATUSES.find((s) => s.value === status)?.label ?? status;
}

export const TASK_STATUSES: { value: PickTaskStatus; label: string }[] = [
  { value: 'OPEN', label: 'Open' },
  { value: 'IN_PROGRESS', label: 'Picking' },
  { value: 'PICKED', label: 'Picked' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

export function taskStatusLabel(status: PickTaskStatus | PickDetailStatus): string {
  if (status === 'SHORT') return 'Short';
  return TASK_STATUSES.find((s) => s.value === status)?.label ?? status;
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
  pickedQuantity: number;
  packedQuantity: number;
  shippedQuantity: number;
  /** Closed short: will never be shipped. */
  closedQuantity: number;
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
  status: PickDetailStatus;
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
  status: PickDetailStatus;
  pickedQuantity: number;
  pickedBy: string | null;
  pickedAt: string | null;
  /** The allocation this detail picks; null once cancelled. */
  allocationId: string | null;
}

/** A pick task, made of one detail per allocation, for one order (discrete) or several (batch). */
export interface PickTaskView {
  id: string;
  taskNumber: string;
  pickMethod: PickMethod;
  /** Code of the pick zone everything on it is in. */
  pickZone: string | null;
  /** The orders it picks for, in detail order. */
  orders: OrderRef[];
  status: PickTaskStatus;
  detailCount: number;
  lineCount: number;
  locationCount: number;
  totalQuantity: number;
  pickedQuantity: number;
  /** Name of the picker working on it. */
  assignedTo: string | null;
  /** The outbound container it is picked into. */
  toContainer: { id: string; lpnId: string } | null;
  /** The pack station the container was dropped at. */
  dropLocation: { id: string; locationId: string } | null;
  details: PickTaskDetailView[];
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
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
  shipTo: ShipTo | null;
  lines: OrderLineView[];
  allocations: AllocationView[];
  tasks: PickTaskView[];
  packTasks: PackTask[];
  shipments: Shipment[];
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
  /** Where it goes, if known yet. */
  shipTo?: ShipTo;
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
  /** The batch task, the first if there are several, or null if no stock could be reserved for any of the orders. */
  task: PickTaskView | null;
  /** All of them: one for each pick zone. */
  tasks: PickTaskView[];
  orders: OrderRef[];
}

/** Allocates several orders onto one batch pick task. */
export function batchAllocate(orderIds: string[]): Promise<BatchAllocation> {
  return request('/api/orders/batch-allocate', { method: 'POST', body: JSON.stringify({ orderIds }) });
}

/** Whether an order still needs stock and so can be allocated (alone or in a batch). */
export function canAllocate(order: { status: OrderStatus; orderedQuantity: number; allocatedQuantity: number }): boolean {
  return order.status !== 'CANCELLED' && order.allocatedQuantity < order.orderedQuantity;
}

export function allocateOrder(id: string): Promise<OrderDetail> {
  return request(`/api/orders/${id}/allocate`, { method: 'POST' });
}

/** Gives up on what isn't picked; what is picked still ships. */
export function closeOrderShort(id: string): Promise<OrderDetail> {
  return request(`/api/orders/${id}/close-short`, { method: 'POST' });
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
  pickZone: string;
  page: number;
  size: number;
}

export function listPickTasks(query: PickTaskQuery, signal?: AbortSignal): Promise<Page<PickTaskView>> {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.status) params.set('status', query.status);
  if (query.pickMethod) params.set('pickMethod', query.pickMethod);
  if (query.pickZone) params.set('pickZone', query.pickZone);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return request(`/api/pick-tasks?${params}`, { signal });
}

export function getPickTask(id: string, signal?: AbortSignal): Promise<PickTaskView> {
  return request(`/api/pick-tasks/${id}`, { signal });
}
