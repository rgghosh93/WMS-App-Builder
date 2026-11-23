import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Adjustment, ControlItem, CountDetail, ItemOnHand, OnHand, Reason, Stock, Transaction } from '../../../api/control';
import { SessionContext } from '../../../session/SessionContext';
import type { Permission } from '../../../api/session';
import { AdjustmentsPage } from './AdjustmentsPage';
import { CycleCountDetailPage } from './CycleCountDetailPage';
import { CycleCountsPage } from './CycleCountsPage';
import { HoldsPage } from './HoldsPage';
import { InventoryByItemPage } from './InventoryByItemPage';
import { ReasonCodesPage } from './ReasonCodesPage';
import { InventoryMovesPage, StockHistoryPage } from './StockHistoryPage';

const BAR: ControlItem = { id: 'i1', itemId: 'SKU-BAR', description: 'Protein bar', lotControlled: true, serialControlled: false, expiryControlled: true };
const SUM: OnHand = { lotNumber: null, expiryDate: null, total: 100, available: 70, reserved: 20, onHold: 10, damaged: 0, expired: 0, elsewhere: 0 };
const ITEM: ItemOnHand = { item: BAR, total: SUM, lots: [{ ...SUM, lotNumber: 'L1', expiryDate: '2027-06-01' }] };
const STOCK: Stock = {
  id: 's1', item: BAR, quantity: 40, reserved: 5, lotNumber: 'L1', serialNumber: null, expiryDate: null, status: 'ON_HOLD', holdReason: 'Recall: batch 7',
  heldAt: '2026-11-22T10:00:00Z', expired: false, locationId: 'l1', location: 'DC1-BK-01', locationType: 'STORAGE', containerId: null, lpn: null,
  receivedAt: '2026-11-01T10:00:00Z',
};
const TRANSACTION: Transaction = {
  id: 't1', number: 9, occurredAt: '2026-11-22T10:00:00Z', type: 'HOLD', itemId: 'i1', itemCode: 'SKU-BAR', lotNumber: 'L1', serialNumber: null, expiryDate: null,
  quantity: 40, fromLocation: 'DC1-BK-01', fromLpn: null, toLocation: null, toLpn: 'TOTE1', fromStatus: 'AVAILABLE', toStatus: 'ON_HOLD', reference: 'PT000009',
  reason: 'Short pick', userName: 'Ann Lee',
};
const ADJUSTMENT: Adjustment = {
  id: 'a1', number: 'AD000001', status: 'PENDING', item: BAR, place: 'DC1-BK-01', lotNumber: 'L1', serialNumber: null, expiryDate: null, stockStatus: 'AVAILABLE',
  change: -50, reason: 'Damaged: fell off', requester: 'Ann Lee', requestedBy: 'ann', requestedAt: '2026-11-22T10:00:00Z', decider: null, decidedAt: null,
};
const REASONS: Reason[] = [
  { id: 'r1', kind: 'ADJUSTMENT', code: 'DAMAGED', name: 'Damaged', status: 'ACTIVE', version: 0 },
  { id: 'r2', kind: 'HOLD', code: 'RECALL', name: 'Recall', status: 'ACTIVE', version: 3 },
  { id: 'r3', kind: 'HOLD', code: 'OLD', name: 'Old one', status: 'INACTIVE', version: 0 },
];
const COUNT: CountDetail = {
  task: {
    id: 'c1', number: 'CC000001', status: 'REVIEW', locationId: 'l1', location: 'DC1-BK-01', plannedBy: 'The Boss', plannedAt: '2026-11-22T09:00:00Z', counter: 'Ann Lee',
    counterId: 'ann', countedAt: '2026-11-22T10:00:00Z', decider: null, decidedAt: null, rounds: 1, expected: 100, counted: 20,
  },
  results: [{ lpn: null, item: BAR, lotNumber: 'L1', serialNumber: null, expected: 100, counted: 20, difference: -80 }],
};

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let urls: string[];
let replies: Record<string, { status?: number; body: unknown }[]>;

beforeEach(() => {
  sent = [];
  urls = [];
  replies = {};
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
      const page = (row: unknown) => json({ content: [row], page: 0, size: 25, totalElements: 1, totalPages: 1 });
      const method = init.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
        const reply = replies[path]?.shift();
        return reply ? json(reply.body, reply.status) : method === 'DELETE' ? json(null, 204) : json({ detail: 'Not found' }, 404);
      }
      urls.push(path);
      const name = path.replace('/api/inventory/', '').split('?')[0];
      if (path.startsWith('/api/zones')) return json([{ id: 'z1', function: 'COUNTING', code: 'WEEKLY', name: 'Weekly', status: 'ACTIVE', priority: null, allocatable: true, locationCount: 3, version: 0 }]);
      if (path.startsWith('/api/items')) return page({ ...BAR, status: 'ACTIVE' });
      if (name === 'reasons') return json(REASONS);
      if (name === 'settings') return json({ approvalLimit: 25 });
      if (name === 'counts/c1') return json(COUNT);
      return page({ 'on-hand': ITEM, stock: STOCK, history: TRANSACTION, adjustments: ADJUSTMENT, counts: COUNT.task }[name]);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function open(path: string, me = 'boss', permissions: Permission[] = ['DESKTOP', 'INVENTORY', 'INVENTORY_APPROVE']) {
  const user = { id: me, userCode: me, displayName: me, permissions, mustChangePassword: false };
  render(
    <SessionContext.Provider value={{ user, signOut: () => undefined, changePassword: () => undefined }}>
      <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/inventory/by-item" element={<InventoryByItemPage />} />
          <Route path="/inventory/history" element={<StockHistoryPage />} />
          <Route path="/inventory/moves" element={<InventoryMovesPage />} />
          <Route path="/inventory/adjustments" element={<AdjustmentsPage />} />
          <Route path="/inventory/holds" element={<HoldsPage />} />
          <Route path="/inventory/counts" element={<CycleCountsPage />} />
          <Route path="/inventory/counts/:id" element={<CycleCountDetailPage />} />
          <Route path="/inventory/reasons" element={<ReasonCodesPage />} />
        </Routes>
      </MemoryRouter>
    </SessionContext.Provider>,
  );
}

describe('Looking at stock', () => {
  it('shows each item with its lots', async () => {
    const user = userEvent.setup();
    open('/inventory/by-item');
    const table = await screen.findByRole('table', { name: 'Inventory by item' });
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('SKU-BARProtein bar1007020—10——');
    await user.click(screen.getByRole('button', { name: 'Lots of SKU-BAR' }));
    expect(within(table).getAllByRole('row')[2]).toHaveTextContent('Lot L1 · expires');
  });

  it('shows the history, searched and filtered in the address', async () => {
    const user = userEvent.setup();
    open('/inventory/history?page=2');
    const row = (await screen.findAllByRole('row'))[1];
    expect(row).toHaveTextContent('HeldAvailable → On hold');
    expect(row).toHaveTextContent('TOTE1carried');
    expect(row).toHaveTextContent('PT000009Short pickAnn Lee');
    expect(urls[0]).toContain('page=2');
    await user.selectOptions(screen.getByLabelText('Change'), 'Adjusted');
    await vi.waitFor(() => expect(urls[urls.length - 1]).toContain('type=ADJUST'));
    expect(urls[urls.length - 1]).toContain('page=0');
    await user.type(screen.getByLabelText('Search stock history'), 'l1');
    await vi.waitFor(() => expect(urls[urls.length - 1]).toContain('q=l1'));
  });

  it('shows only moves on the moves page', async () => {
    open('/inventory/moves?type=SHIP');
    await screen.findAllByRole('row');
    expect(urls[0]).toContain('type=MOVE');
    expect(urls[0]).not.toContain('SHIP');
  });
});

describe('Adjustments', () => {
  it('corrects a quantity, saying when it needs approval', async () => {
    const user = userEvent.setup();
    open('/inventory/adjustments');
    expect(await screen.findByText(/More than 25 EA at once needs a second person/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Correct a quantity' }));
    await user.click(screen.getByRole('button', { name: 'Correct it' }));
    expect(screen.getByText('Choose the stock to correct')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Stock'), 'bar');
    await user.click(await screen.findByRole('option', { name: /SKU-BAR/ }));
    expect(screen.getByText(/There are 40 EA on record, 5 of them reserved/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Really there (EA)'), '39');
    expect(screen.getByRole('status')).toHaveTextContent('1 EA less');
    await user.clear(screen.getByLabelText('Really there (EA)'));
    await user.type(screen.getByLabelText('Really there (EA)'), '10');
    expect(screen.getByRole('status')).toHaveTextContent('30 EA less. Someone else will have to approve this.');
    await user.selectOptions(screen.getByLabelText('Reason'), 'Damaged');
    await user.type(screen.getByLabelText('Note'), 'fell off');
    replies['/api/inventory/adjustments/change'] = [
      { status: 409, body: { detail: 'There are now 41 EA. Check again before correcting it.' } },
      { body: ADJUSTMENT },
    ];
    await user.click(screen.getByRole('button', { name: 'Correct it' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('There are now 41 EA');
    await user.click(screen.getByRole('button', { name: 'Correct it' }));
    expect(await screen.findByText('AD000001 is waiting for someone else to approve it')).toBeInTheDocument();
    expect(sent[1].body).toEqual({ inventoryId: 's1', expectedQuantity: 40, newQuantity: 10, reasonId: 'r1', note: 'fell off' });
  });

  it('adds found stock with what the item needs', async () => {
    const user = userEvent.setup();
    open('/inventory/adjustments');
    await user.click(await screen.findByRole('button', { name: 'Add found stock' }));
    await user.type(screen.getByLabelText('Item'), 'bar');
    await user.click(await screen.findByRole('option', { name: /SKU-BAR/ }));
    await user.click(screen.getByRole('button', { name: 'Add it' }));
    expect(screen.getByText('Enter the lot')).toBeInTheDocument();
    expect(screen.getByText('Enter the location')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Found'), 'In an LPN');
    await user.type(screen.getByLabelText('LPN'), 'p1');
    await user.type(screen.getByLabelText('Lot'), 'l1');
    await user.type(screen.getByLabelText('Expiry date'), '2027-06-01');
    await user.type(screen.getByLabelText('Quantity (EA)'), '3');
    await user.selectOptions(screen.getByLabelText('Reason'), 'Damaged');
    replies['/api/inventory/adjustments/add'] = [{ body: { ...ADJUSTMENT, status: 'APPLIED' } }];
    await user.click(screen.getByRole('button', { name: 'Add it' }));
    expect(await screen.findByText('AD000001 made')).toBeInTheDocument();
    expect(sent[0].body).toMatchObject({ itemId: 'SKU-BAR', locationId: null, lpnId: 'P1', quantity: 3, lotNumber: 'L1', expiryDate: '2027-06-01', serialNumber: null });
  });

  it('is approved by someone else only', async () => {
    const user = userEvent.setup();
    open('/inventory/adjustments');
    replies['/api/inventory/adjustments/a1/approve'] = [{ body: { ...ADJUSTMENT, status: 'APPLIED' } }];
    await user.click(await screen.findByRole('button', { name: 'Approve AD000001' }));
    expect(await screen.findByText('AD000001 approved')).toBeInTheDocument();
  });

  it('offers the one who asked only to reject', async () => {
    open('/inventory/adjustments', 'ann');
    expect(await screen.findByRole('button', { name: 'Reject AD000001' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve AD000001' })).not.toBeInTheDocument();
  });

  it('is only to look at for others', async () => {
    open('/inventory/adjustments', 'vic', ['DESKTOP']);
    await screen.findByText('AD000001');
    expect(screen.queryByRole('button', { name: 'Correct a quantity' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reject AD000001' })).not.toBeInTheDocument();
  });
});

describe('Holds', () => {
  it('checks how much before holding a lot', async () => {
    const user = userEvent.setup();
    open('/inventory/holds');
    await user.click(await screen.findByRole('button', { name: 'Put on hold' }));
    await user.selectOptions(screen.getByLabelText('What'), 'A lot');
    await user.click(screen.getByRole('button', { name: 'Check' }));
    expect(screen.getByText('Enter the lot')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Item'), 'sku-bar');
    await user.type(screen.getByLabelText('Lot'), 'l1');
    expect(screen.queryByRole('option', { name: 'Old one' })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Reason'), 'Recall');
    replies['/api/inventory/holds/preview'] = [{ body: { records: 2, quantity: 40, ordersReallocated: 0, stuck: 5 } }];
    await user.click(screen.getByRole('button', { name: 'Check' }));
    expect(await screen.findByText(/40 EA of good stock in 2 places. 5 EA are reserved/)).toBeInTheDocument();
    replies['/api/inventory/holds'] = [{ body: { records: 2, quantity: 40, ordersReallocated: 1, stuck: 2 } }];
    await user.click(screen.getByRole('button', { name: 'Hold 40 EA' }));
    expect(await screen.findByText('Held 40 EA. 1 order looked for other stock. 2 EA already being picked or picked stay with their orders')).toBeInTheDocument();
    expect(sent[1].body).toMatchObject({ scope: { itemId: 'SKU-BAR', lotNumber: 'L1' }, damaged: false, reasonId: 'r2' });
  });

  it('releases what is selected', async () => {
    const user = userEvent.setup();
    open('/inventory/holds');
    expect(await screen.findByText('Recall: batch 7')).toBeInTheDocument();
    expect(urls.find((u) => u.includes('stock'))).toContain('held=true');
    expect(screen.getByRole('button', { name: 'Release' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Select SKU-BAR at DC1-BK-01' }));
    await user.click(screen.getByRole('button', { name: 'Release 1' }));
    await user.selectOptions(await screen.findByLabelText('Reason'), 'Recall');
    replies['/api/inventory/holds/release'] = [{ body: { records: 1, quantity: 40, ordersReallocated: 0, stuck: 0 } }];
    await user.click(screen.getByRole('button', { name: 'Release 40 EA' }));
    expect(await screen.findByText('Released 40 EA')).toBeInTheDocument();
    expect(sent[0].body).toEqual({ inventoryIds: ['s1'], reasonId: 'r2', note: '' });
  });
});

describe('Counts', () => {
  it('plans a counting zone', async () => {
    const user = userEvent.setup();
    open('/inventory/counts');
    await user.click(await screen.findByRole('button', { name: 'Plan counts' }));
    const dialog = within(screen.getByRole('dialog'));
    await user.click(dialog.getByRole('button', { name: 'Plan counts' }));
    expect(await dialog.findByText('Choose a zone', { selector: '.field__error' })).toBeInTheDocument();
    await dialog.findByRole('option', { name: 'WEEKLY · Weekly' });
    await user.selectOptions(dialog.getByLabelText('Counting zone'), 'z1');
    replies['/api/inventory/counts'] = [{ body: { created: 3, skipped: 0 } }];
    await user.click(dialog.getByRole('button', { name: 'Plan counts' }));
    expect(await screen.findByText('Planned 3 counts')).toBeInTheDocument();
    expect(sent[0].body).toEqual({ zoneId: 'z1', includeEmpty: false });
  });

  it('plans a zone of the address', async () => {
    const user = userEvent.setup();
    open('/inventory/counts');
    expect(await screen.findByText('To review', { selector: '.badge' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Plan counts' }));
    const dialog = within(screen.getByRole('dialog'));
    await user.selectOptions(dialog.getByLabelText('Count'), 'A zone of the address');
    await user.type(dialog.getByLabelText('Area'), 'dc1');
    await user.type(dialog.getByLabelText('Zone'), 'bk');
    replies['/api/inventory/counts'] = [{ body: { created: 12, skipped: 1 } }];
    await user.click(dialog.getByRole('button', { name: 'Plan counts' }));
    expect(await screen.findByText('Planned 12 counts; 1 already had one')).toBeInTheDocument();
    expect(sent[0].body).toEqual({ area: 'DC1', zone: 'BK', includeEmpty: false });
  });

  it('is reviewed by someone else', async () => {
    const user = userEvent.setup();
    open('/inventory/counts/c1');
    expect(await screen.findByText('−80')).toBeInTheDocument();
    replies['/api/inventory/counts/c1/approve'] = [{ body: { ...COUNT, task: { ...COUNT.task, status: 'COMPLETED', decider: 'boss', decidedAt: '2026-11-22T11:00:00Z' } } }];
    await user.click(screen.getByRole('button', { name: 'Approve differences' }));
    expect(await screen.findByText('Counted', { selector: '.badge' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Count again' })).not.toBeInTheDocument();
  });

  it('cannot be approved by whoever counted', async () => {
    open('/inventory/counts/c1', 'ann');
    expect(await screen.findByText('Someone else has to approve what you counted.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve differences' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Count again' })).toBeInTheDocument();
  });
});

describe('Reason codes', () => {
  it('keeps reasons and the limit', async () => {
    const user = userEvent.setup();
    open('/inventory/reasons');
    const limit = await screen.findByLabelText('Approval limit (EA)');
    await user.clear(limit);
    await user.type(limit, '50');
    replies['/api/inventory/settings'] = [{ body: { approvalLimit: 50 } }];
    await user.click(screen.getByRole('button', { name: 'Save limit' }));
    expect(await screen.findByText('The limit is 50 EA')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: 'RECALL' }));
    await user.selectOptions(screen.getByLabelText('Status'), 'Inactive');
    replies['/api/inventory/reasons/r2'] = [{ body: REASONS[1] }];
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Saved reason RECALL')).toBeInTheDocument();
    expect(sent[1].body).toEqual({ kind: 'HOLD', code: 'RECALL', name: 'Recall', status: 'INACTIVE', version: 3 });
  });
});
