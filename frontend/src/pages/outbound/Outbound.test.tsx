import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OrdersPage } from './OrdersPage';
import { OrderDetailPage } from './OrderDetailPage';
import { PickTasksPage } from '../picking/PickTasksPage';
import { PickTaskDetailPage } from '../picking/PickTaskDetailPage';
import type { OrderDetail, OrderRef, OrderSummary, PickTaskDetailView, PickTaskView } from '../../api/orders';
import type { Item } from '../../api/items';

const SUMMARIES: OrderSummary[] = [
  {
    id: 'o2',
    orderNumber: 'SO001002',
    customerName: 'Bluebird Grocers',
    requestedShipDate: '2026-11-22',
    priority: 5,
    status: 'PARTIALLY_ALLOCATED',
    lineCount: 2,
    orderedQuantity: 80,
    allocatedQuantity: 77,
    openTaskCount: 6,
    createdAt: '2026-11-20T15:00:00Z',
  },
  {
    id: 'o4',
    orderNumber: 'SO001004',
    customerName: 'Summit Outfitters',
    requestedShipDate: null,
    priority: 7,
    status: 'BACKORDERED',
    lineCount: 1,
    orderedQuantity: 10,
    allocatedQuantity: 0,
    openTaskCount: 0,
    createdAt: '2026-11-20T15:01:00Z',
  },
];

const O2: OrderRef = { id: 'o2', orderNumber: 'SO001002', customerName: 'Bluebird Grocers', status: 'PARTIALLY_ALLOCATED' };
const O4: OrderRef = { id: 'o4', orderNumber: 'SO001004', customerName: 'Summit Outfitters', status: 'ALLOCATED' };

/** A batch task picking for SO001002 (details 1-2) and SO001004 (detail 3). */
function batchTask(): PickTaskView {
  return task(3, {
    pickMethod: 'BATCH',
    details: [
      detail(1),
      detail(2, { quantity: 4, lotNumber: 'PB2611B' }),
      detail(3, { order: O4, item: { id: 'i9', itemId: 'SKU-20004', description: 'Granola' }, quantity: 10, lotNumber: 'GR2610' }),
    ],
  });
}

function detail(n: number, extra: Partial<PickTaskDetailView> = {}): PickTaskDetailView {
  return {
    id: `d${n}`,
    detailNumber: n,
    order: O2,
    lineNumber: 1,
    item: { id: 'i1', itemId: 'SKU-20001', description: 'Protein bar' },
    quantity: 6,
    fromLocation: { id: 'l1', locationId: 'DC1-RS-01-01-A-01' },
    fromContainer: { id: 'c1', lpnId: 'C00000001' },
    lotNumber: 'PB2610A',
    serialNumber: null,
    status: 'OPEN',
    allocationId: `a${n}`,
    ...extra,
  };
}

function task(n: number, extra: Partial<PickTaskView> = {}): PickTaskView {
  const details = extra.details ?? [
    detail(1),
    detail(2, { quantity: 21, fromLocation: { id: 'l2', locationId: 'DC1-BK-01-01-A-02' }, fromContainer: null, lotNumber: 'PB2611B' }),
  ];
  return {
    id: `t${n}`,
    taskNumber: `PT00000${n}`,
    pickMethod: 'DISCRETE',
    orders: [...new Map(details.map((d) => [d.order.id, d.order])).values()],
    status: 'OPEN',
    detailCount: details.length,
    lineCount: new Set(details.map((d) => d.lineNumber)).size,
    locationCount: new Set(details.map((d) => d.fromLocation.id)).size,
    totalQuantity: details.reduce((sum, d) => sum + d.quantity, 0),
    details,
    createdAt: '2026-11-20T15:00:01Z',
    ...extra,
  };
}

const DETAIL: OrderDetail = {
  id: 'o2',
  orderNumber: 'SO001002',
  customerName: 'Bluebird Grocers',
  requestedShipDate: '2026-11-22',
  priority: 5,
  status: 'PARTIALLY_ALLOCATED',
  lines: [
    {
      id: 'l1',
      lineNumber: 1,
      item: { id: 'i1', itemId: 'SKU-20001', description: 'Protein bar' },
      orderedQuantity: 30,
      allocatedQuantity: 27,
      requestedLot: null,
      status: 'PARTIALLY_ALLOCATED',
    },
    {
      id: 'l2',
      lineNumber: 2,
      item: { id: 'i2', itemId: 'SKU-20002', description: 'Sparkling water' },
      orderedQuantity: 50,
      allocatedQuantity: 50,
      requestedLot: null,
      status: 'ALLOCATED',
    },
  ],
  allocations: [
    {
      id: 'a1',
      order: { id: 'o2', orderNumber: 'SO001002', customerName: 'Bluebird Grocers', status: 'PARTIALLY_ALLOCATED' },
      lineNumber: 1,
      item: { id: 'i1', itemId: 'SKU-20001', description: 'Protein bar' },
      quantity: 6,
      location: { id: 'l1', locationId: 'DC1-RS-01-01-A-01' },
      container: { id: 'c1', lpnId: 'C00000001' },
      lotNumber: 'PB2610A',
      serialNumber: null,
      expiryDate: '2027-04-15',
      inventoryStatus: 'AVAILABLE',
      task: { id: 't1', taskNumber: 'PT000001', detailNumber: 1, status: 'OPEN' },
      createdAt: '2026-11-20T15:00:01Z',
    },
    {
      id: 'a2',
      order: { id: 'o2', orderNumber: 'SO001002', customerName: 'Bluebird Grocers', status: 'PARTIALLY_ALLOCATED' },
      lineNumber: 1,
      item: { id: 'i1', itemId: 'SKU-20001', description: 'Protein bar' },
      quantity: 21,
      location: { id: 'l2', locationId: 'DC1-BK-01-01-A-02' },
      container: null,
      lotNumber: 'PB2611B',
      serialNumber: null,
      expiryDate: '2027-05-01',
      inventoryStatus: 'AVAILABLE',
      task: { id: 't1', taskNumber: 'PT000001', detailNumber: 2, status: 'OPEN' },
      createdAt: '2026-11-20T15:00:01Z',
    },
  ],
  tasks: [task(1), task(2, { status: 'CANCELLED', details: [detail(1, { status: 'CANCELLED', allocationId: null })] })],
  events: [
    { occurredAt: '2026-11-20T15:00:00Z', type: 'RECEIVED', message: 'Order received from Bluebird Grocers: 2 lines, 80 EA' },
    { occurredAt: '2026-11-20T15:00:01Z', type: 'SHORT', message: 'Line 1: allocated 27 EA of SKU-20001; 3 EA short' },
    { occurredAt: '2026-11-20T15:00:01Z', type: 'TASKS_CREATED', message: 'Created 7 pick tasks: PT000001 to PT000007' },
  ],
  createdAt: '2026-11-20T15:00:00Z',
  updatedAt: '2026-11-20T15:00:01Z',
  version: 1,
};

const ITEMS: Partial<Item>[] = [
  { id: 'i1', itemId: 'SKU-20001', description: 'Protein bar', status: 'ACTIVE', lotControlled: true },
  { id: 'i3', itemId: 'SKU-10004', description: 'Work gloves', status: 'ACTIVE', lotControlled: false },
];

let calls: { method: string; url: URL; body?: Record<string, unknown> }[];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  calls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit = {}) => {
      const url = new URL(input, 'http://localhost');
      const method = init.method ?? 'GET';
      const body = init.body ? JSON.parse(String(init.body)) : undefined;
      calls.push({ method, url, body });
      if (url.pathname === '/api/items') {
        const q = (url.searchParams.get('q') ?? '').toLowerCase();
        const content = ITEMS.filter((i) => i.itemId!.toLowerCase().includes(q));
        return json({ content, page: 0, size: 8, totalElements: content.length, totalPages: 1 });
      }
      if (url.pathname === '/api/orders' && method === 'GET') {
        return json({ content: SUMMARIES, page: 0, size: 25, totalElements: 2, totalPages: 1 });
      }
      if (url.pathname === '/api/orders' && method === 'POST') return json({ ...DETAIL, id: 'new', orderNumber: 'SO001005' }, 201);
      if (url.pathname === '/api/orders/o2') return json(DETAIL);
      if (url.pathname === '/api/orders/new') return json({ ...DETAIL, id: 'new', orderNumber: 'SO001005' });
      if (url.pathname === '/api/orders/o2/allocate') return json({ ...DETAIL, status: 'ALLOCATED' });
      if (url.pathname === '/api/orders/o2/cancel') {
        return json({ ...DETAIL, status: 'CANCELLED', allocations: [], tasks: DETAIL.tasks.map((t) => ({ ...t, status: 'CANCELLED' })) });
      }
      if (url.pathname === '/api/pick-tasks') {
        return json({ content: [batchTask(), task(1), task(2, { details: [detail(1, { serialNumber: 'DR18-000101' })] })], page: 0, size: 25, totalElements: 3, totalPages: 1 });
      }
      if (url.pathname === '/api/pick-tasks/t1') return json(task(1));
      if (url.pathname === '/api/pick-tasks/t3') return json(batchTask());
      if (url.pathname === '/api/orders/batch-allocate') return json({ task: batchTask(), orders: [O2, O4] });
      if (url.pathname === '/api/orders/o5') return json({ ...DETAIL, tasks: [batchTask()] });
      return json({ detail: 'Not found' }, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/outbound/orders" element={<OrdersPage />} />
        <Route path="/outbound/orders/:id" element={<OrderDetailPage />} />
        <Route path="/picking/tasks" element={<PickTasksPage />} />
        <Route path="/picking/tasks/:id" element={<PickTaskDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Orders list', () => {
  it('shows each order with its status and allocation progress', async () => {
    renderAt('/outbound/orders');
    const row = (await screen.findByRole('link', { name: 'SO001002' })).closest('tr')!;
    expect(within(row).getByText('Partially allocated')).toBeInTheDocument();
    expect(within(row).getByText('77 / 80 EA')).toBeInTheDocument();
    expect(within(row).getByText('Bluebird Grocers')).toBeInTheDocument();
    const backordered = screen.getByRole('link', { name: 'SO001004' }).closest('tr')!;
    expect(within(backordered).getByText('Backordered')).toBeInTheDocument();
    expect(calls.find((c) => c.url.pathname === '/api/orders')!.url.searchParams.get('sort')).toBe('createdAt');
  });

  it('filters by status', async () => {
    const user = userEvent.setup();
    renderAt('/outbound/orders');
    await screen.findByRole('link', { name: 'SO001002' });
    await user.selectOptions(screen.getByLabelText('Status'), 'BACKORDERED');
    await waitFor(() => expect(calls.some((c) => c.url.searchParams.get('status') === 'BACKORDERED')).toBe(true));
  });

  it('creates an order with lines and opens it', async () => {
    const user = userEvent.setup();
    renderAt('/outbound/orders');
    await user.click(await screen.findByRole('button', { name: 'New order' }));
    const dialog = screen.getByRole('dialog', { name: 'New order' });

    await user.type(within(dialog).getByLabelText('Customer'), 'Lakeside Cafe');
    await user.selectOptions(within(dialog).getByLabelText('Priority'), '2');

    const line1 = within(dialog).getByRole('listitem', { name: 'Line 1' });
    expect(within(line1).getByLabelText('Lot')).toBeDisabled();
    await user.type(within(line1).getByRole('combobox', { name: 'Item' }), '2000');
    await user.click(await within(line1).findByRole('option', { name: /SKU-20001/ }));
    expect(within(line1).getByLabelText('Lot')).toBeEnabled();
    await user.type(within(line1).getByLabelText('Quantity (EA)'), '12');
    await user.type(within(line1).getByLabelText('Lot'), 'pb2610a');

    await user.click(within(dialog).getByRole('button', { name: 'Add line' }));
    const line2 = within(dialog).getByRole('listitem', { name: 'Line 2' });
    await user.type(within(line2).getByRole('combobox', { name: 'Item' }), 'glove');
    await waitFor(() => expect(within(line2).queryByText('No matching active items')).toBeInTheDocument());
    await user.clear(within(line2).getByRole('combobox', { name: 'Item' }));
    await user.type(within(line2).getByRole('combobox', { name: 'Item' }), '10004');
    await user.click(await within(line2).findByRole('option', { name: /SKU-10004/ }));
    await user.type(within(line2).getByLabelText('Quantity (EA)'), '4');

    await user.click(within(dialog).getByRole('button', { name: 'Create order' }));

    expect(await screen.findByRole('heading', { name: /SO001005/ })).toBeInTheDocument();
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      orderNumber: null,
      customerName: 'Lakeside Cafe',
      requestedShipDate: null,
      priority: 2,
      lines: [
        { itemId: 'SKU-20001', quantity: 12, requestedLot: 'PB2610A' },
        { itemId: 'SKU-10004', quantity: 4, requestedLot: null },
      ],
    });
  });

  it('allocates selected orders together on one batch task', async () => {
    const user = userEvent.setup();
    renderAt('/outbound/orders');
    await user.click(await screen.findByRole('checkbox', { name: 'Select SO001002 for batch' }));
    const bar = screen.getByRole('group', { name: 'Selected orders' });
    expect(within(bar).getByRole('button', { name: 'Batch allocate' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Select SO001004 for batch' }));
    await user.click(within(bar).getByRole('button', { name: 'Batch allocate' }));

    expect(await screen.findByRole('heading', { name: /PT000003/ })).toBeInTheDocument();
    expect(calls.find((c) => c.url.pathname === '/api/orders/batch-allocate')!.body).toEqual({ orderIds: ['o2', 'o4'] });
  });

  it('can hold a new order back to batch it later', async () => {
    const user = userEvent.setup();
    renderAt('/outbound/orders');
    await user.click(await screen.findByRole('button', { name: 'New order' }));
    const dialog = screen.getByRole('dialog', { name: 'New order' });
    const allocateNow = within(dialog).getByRole('checkbox', { name: /Allocate now/ });
    expect(allocateNow).toBeChecked();
    await user.click(allocateNow);
    expect(within(dialog).getByText(/stays New, so you can allocate it later/)).toBeInTheDocument();
  });

  it('checks the order before sending it', async () => {
    const user = userEvent.setup();
    renderAt('/outbound/orders');
    await user.click(await screen.findByRole('button', { name: 'New order' }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Create order' }));

    expect(within(dialog).getByText('Enter the customer')).toBeInTheDocument();
    expect(within(dialog).getByText('Choose an item')).toBeInTheDocument();
    expect(within(dialog).getByText('Required')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });
});

describe('Order detail', () => {
  it('shows lines, allocations, pick tasks and activity', async () => {
    renderAt('/outbound/orders/o2');

    expect(await screen.findByRole('heading', { name: /SO001002/ })).toBeInTheDocument();
    const lines = screen.getByRole('table', { name: 'Order lines' });
    expect(within(lines).getByText('27 / 30 EA')).toBeInTheDocument();
    expect(within(lines).getAllByText('Partially allocated')).toHaveLength(1);

    const allocations = screen.getByRole('table', { name: 'Allocations' });
    expect(within(allocations).getByRole('link', { name: 'DC1-RS-01-01-A-01' })).toHaveAttribute(
      'href',
      '/inventory/by-location?location=l1',
    );
    expect(within(allocations).getByRole('link', { name: 'C00000001' })).toBeInTheDocument();
    expect(within(allocations).getByText('PB2610A')).toBeInTheDocument();
    // Line 1 is split across two allocations, each a detail of the same task.
    expect(within(allocations).getByRole('rowheader', { name: /Line 1 · SKU-20001 · 27 of 30 EA from 2 allocations/ })).toBeInTheDocument();
    const second = within(allocations).getByText('PB2611B').closest('tr')!;
    expect(within(second).getByRole('link', { name: 'PT000001' })).toHaveAttribute('href', '/picking/tasks/t1');
    expect(within(second).getByText('· detail 2')).toBeInTheDocument();
    const firstLine = within(lines).getByText('SKU-20001').closest('tr')!;
    expect(within(firstLine).getByText('2')).toBeInTheDocument();

    const details = screen.getByRole('table', { name: 'Details of PT000001' });
    expect(within(details).getAllByRole('row')).toHaveLength(3);
    expect(within(details).getByText('DC1-BK-01-01-A-02')).toBeInTheDocument();
    expect(screen.getByText('2 details · 1 line · 27 EA')).toBeInTheDocument();
    const cancelled = screen.getByRole('table', { name: 'Details of PT000002' });
    expect(within(cancelled).getByText('Cancelled')).toBeInTheDocument();

    expect(screen.getByText('Line 1: allocated 27 EA of SKU-20001; 3 EA short')).toBeInTheDocument();
    expect(screen.getByText('Created 7 pick tasks: PT000001 to PT000007')).toBeInTheDocument();
  });

  it("shows only this order's picks from a batch task", async () => {
    renderAt('/outbound/orders/o5');
    await screen.findByRole('heading', { name: /SO001002/ });
    expect(screen.getByText('2 of 3 details are for this order · batched with SO001004')).toBeInTheDocument();
    const details = screen.getByRole('table', { name: 'Details of PT000003' });
    expect(within(details).getAllByRole('row')).toHaveLength(3);
    expect(within(details).queryByText('SKU-20004')).not.toBeInTheDocument();
  });

  it('allocates again', async () => {
    const user = userEvent.setup();
    renderAt('/outbound/orders/o2');
    await user.click(await screen.findByRole('button', { name: 'Allocate again' }));

    expect(await screen.findByRole('status')).toHaveTextContent('order is now allocated');
    expect(calls.some((c) => c.method === 'POST' && c.url.pathname === '/api/orders/o2/allocate')).toBe(true);
    expect(screen.queryByRole('button', { name: 'Allocate again' })).not.toBeInTheDocument();
  });

  it('cancels after confirming', async () => {
    const user = userEvent.setup();
    renderAt('/outbound/orders/o2');
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }));

    const confirm = screen.getByRole('group', { name: 'Confirm cancel' });
    expect(confirm).toHaveTextContent('1 open pick task cancelled');
    expect(calls.some((c) => c.url.pathname.endsWith('/cancel'))).toBe(false);
    await user.click(within(confirm).getByRole('button', { name: 'Cancel order' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Order SO001002 cancelled');
    expect(screen.getByText('No stock is reserved for this order.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument();
  });
});

describe('Pick tasks', () => {
  it('lists each task with its order and how many details it has', async () => {
    renderAt('/picking/tasks');
    const table = await screen.findByRole('table', { name: 'Pick tasks' });
    const first = within(table).getByRole('link', { name: 'PT000001' }).closest('tr')!;
    expect(within(table).getByRole('link', { name: 'PT000001' })).toHaveAttribute('href', '/picking/tasks/t1');
    expect(within(first).getByRole('link', { name: 'SO001002' })).toHaveAttribute('href', '/outbound/orders/o2');
    expect(within(first).getByText('27 EA')).toBeInTheDocument();
    const cells = within(first).getAllByRole('cell').map((c) => c.textContent);
    expect(cells.slice(2, 5)).toEqual(['2', '1', '2']);
    expect(within(first).getByText('Discrete')).toBeInTheDocument();

    const batch = within(table).getByRole('link', { name: 'PT000003' }).closest('tr')!;
    expect(within(batch).getByText('Batch')).toBeInTheDocument();
    expect(within(batch).getByRole('link', { name: 'SO001004' })).toHaveAttribute('href', '/outbound/orders/o4');
    expect(within(batch).getByText('2 orders')).toBeInTheDocument();
  });

  it('filters by pick method', async () => {
    const user = userEvent.setup();
    renderAt('/picking/tasks');
    await screen.findByRole('table', { name: 'Pick tasks' });
    await user.selectOptions(screen.getByLabelText('Method'), 'BATCH');
    await waitFor(() => expect(calls.some((c) => c.url.searchParams.get('pickMethod') === 'BATCH')).toBe(true));
  });

  it('shows a batch task with every order and which order each pick is for', async () => {
    renderAt('/picking/tasks/t3');
    expect(await screen.findByRole('heading', { name: /PT000003/ })).toBeInTheDocument();
    expect(screen.getByText('Orders (2)')).toBeInTheDocument();
    const details = screen.getByRole('table', { name: 'Task details' });
    expect(within(details).getByRole('columnheader', { name: 'Order' })).toBeInTheDocument();
    const rows = within(details).getAllByRole('row').slice(1);
    expect(within(rows[2]).getByRole('link', { name: 'SO001004' })).toHaveAttribute('href', '/outbound/orders/o4');
    expect(within(rows[0]).getByRole('link', { name: 'SO001002' })).toBeInTheDocument();
  });

  it('opens a task to show its details', async () => {
    renderAt('/picking/tasks/t1');
    expect(await screen.findByRole('heading', { name: /PT000001/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'SO001002' })).toHaveAttribute('href', '/outbound/orders/o2');
    const details = screen.getByRole('table', { name: 'Task details' });
    const rows = within(details).getAllByRole('row').slice(1);
    expect(rows).toHaveLength(2);
    expect(within(details).queryByRole('columnheader', { name: 'Order' })).not.toBeInTheDocument();
    expect(within(rows[0]).getByRole('link', { name: 'C00000001' })).toBeInTheDocument();
    expect(within(rows[1]).getByText('21 EA')).toBeInTheDocument();
    expect(within(rows[1]).getByText('PB2611B')).toBeInTheDocument();
  });

  it('filters by status', async () => {
    const user = userEvent.setup();
    renderAt('/picking/tasks');
    await screen.findByRole('table', { name: 'Pick tasks' });
    await user.selectOptions(screen.getByLabelText('Status'), 'OPEN');
    await waitFor(() => expect(calls.some((c) => c.url.pathname === '/api/pick-tasks' && c.url.searchParams.get('status') === 'OPEN')).toBe(true));
  });
});
