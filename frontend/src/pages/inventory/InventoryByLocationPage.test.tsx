import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InventoryByLocationPage } from './InventoryByLocationPage';
import type { ContainerNode, InventoryLine, LocationDetail, LocationSummary } from '../../api/inventory';

const SHELF = { id: 'l-shelf', locationId: 'DC1-BK-02-01-A-01', type: 'STORAGE', area: 'DC1', zone: 'BK' };
const RESERVE = { id: 'l-res', locationId: 'DC1-RS-01-01-A-01', type: 'STORAGE', area: 'DC1', zone: 'RS' };

const SUMMARIES: LocationSummary[] = [
  { location: SHELF, totalQuantity: 54, itemCount: 2, containerCount: 0, looseLineCount: 2 },
  { location: RESERVE, totalQuantity: 27, itemCount: 1, containerCount: 5, looseLineCount: 0 },
];

function line(id: string, itemId: string, quantity: number, extra: Partial<InventoryLine> = {}): InventoryLine {
  return {
    id,
    item: { id: `i-${itemId}`, itemId, description: `${itemId} description`, packs: [{ uomCode: 'CS', quantity: 6 }] },
    quantity,
    allocatedQuantity: 0,
    lotNumber: null,
    serialNumber: null,
    expiryDate: null,
    status: 'AVAILABLE',
    receivedAt: '2026-11-14T14:00:00Z',
    ...extra,
  };
}

function lpn(id: string, category: 'PALLET' | 'CARTON', lines: InventoryLine[], children: ContainerNode[] = []): ContainerNode {
  const own = lines.reduce((s, l) => s + l.quantity, 0);
  return {
    id,
    lpnId: id,
    permanent: false,
    containerType: { id: `t-${category}`, containerTypeId: category === 'PALLET' ? 'PLT-GMA' : 'CTN-M', description: '', category },
    status: 'IN_USE',
    totalQuantity: own + children.reduce((s, c) => s + c.totalQuantity, 0),
    itemCount: 1,
    lines,
    children,
  };
}

const SHELF_DETAIL: LocationDetail = {
  location: SHELF,
  totalQuantity: 54,
  itemCount: 2,
  containerCount: 0,
  lines: [
    line('a', 'SKU-20004', 30, { lotNumber: 'GR2610', expiryDate: '2027-08-15' }),
    line('b', 'SKU-10004', 24, { status: 'DAMAGED', allocatedQuantity: 0 }),
    line('h', 'SKU-10003', 60, { allocatedQuantity: 24 }),
  ],
  containers: [],
};

const RESERVE_DETAIL: LocationDetail = {
  location: RESERVE,
  totalQuantity: 27,
  itemCount: 1,
  containerCount: 5,
  lines: [],
  containers: [
    lpn('P00000001', 'PALLET', [line('c', 'SKU-20001', 3, { lotNumber: 'PB2610A' })], [
      lpn('C00000001', 'CARTON', [line('d', 'SKU-20001', 6, { lotNumber: 'PB2610A' })]),
      lpn('C00000002', 'CARTON', [line('e', 'SKU-20001', 6, { lotNumber: 'PB2610A' })]),
      lpn('C00000003', 'CARTON', [line('f', 'SKU-20001', 6, { lotNumber: 'PB2610A' })]),
      lpn('C00000004', 'CARTON', [line('g', 'SKU-20001', 6, { lotNumber: 'PB2610A' })]),
    ]),
  ],
};

let calls: URL[];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  calls = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      const url = new URL(input, 'http://localhost');
      calls.push(url);
      if (url.pathname === '/api/uoms') return json([{ id: 'u', uomCode: 'EA', base: true }]);
      if (url.pathname === '/api/inventory/locations') {
        return json({ content: SUMMARIES, page: 0, size: 25, totalElements: 2, totalPages: 1 });
      }
      if (url.pathname === `/api/inventory/locations/${SHELF.id}`) return json(SHELF_DETAIL);
      if (url.pathname === `/api/inventory/locations/${RESERVE.id}`) return json(RESERVE_DETAIL);
      return json({ detail: 'Not found' }, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderPage(path = '/inventory/by-location') {
  return render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/inventory/by-location" element={<InventoryByLocationPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Inventory by Location', () => {
  it('lists occupied locations with what they hold', async () => {
    renderPage();
    const list = await screen.findByRole('table', { name: 'Locations' });

    const shelf = within(list).getByRole('button', { name: 'DC1-BK-02-01-A-01' }).closest('tr')!;
    expect(within(shelf).getByText('2 items')).toBeInTheDocument();
    expect(within(shelf).getByText('54 EA')).toBeInTheDocument();
    const reserve = within(list).getByRole('button', { name: 'DC1-RS-01-01-A-01' }).closest('tr')!;
    expect(within(reserve).getByText('5 LPNs · 1 item')).toBeInTheDocument();
    expect(calls.find((u) => u.pathname === '/api/inventory/locations')!.searchParams.get('occupied')).toBe('true');
    expect(screen.getByText('Choose a location to see what it holds.')).toBeInTheDocument();
  });

  it('shows loose stock with item, quantity and attributes', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'DC1-BK-02-01-A-01' }));

    const table = await screen.findByRole('table', { name: 'Loose stock in DC1-BK-02-01-A-01' });
    const granola = within(table).getByText('SKU-20004').closest('tr')!;
    expect(within(granola).getByText('30 EA')).toBeInTheDocument();
    expect(within(granola).getByText('5 CS')).toBeInTheDocument();
    expect(within(granola).getByText('GR2610')).toBeInTheDocument();
    expect(within(granola).getByText(/2027/)).toBeInTheDocument();
    expect(within(granola).getByText('Available')).toBeInTheDocument();
    const gloves = within(table).getByText('SKU-10004').closest('tr')!;
    expect(within(gloves).getByText('Damaged')).toBeInTheDocument();
    expect(within(table).queryByRole('columnheader', { name: 'Serial' })).not.toBeInTheDocument();
    const glasses = within(table).getByText('SKU-10003').closest('tr')!;
    expect(within(glasses).getByTitle('Reserved for orders')).toHaveTextContent('24 EA');
  });

  it('shows containers and the containers inside them', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' }));

    const tree = await screen.findByRole('tree', { name: 'Containers in DC1-RS-01-01-A-01' });
    const pallet = within(tree).getAllByRole('treeitem')[0];
    expect(pallet).toHaveAttribute('aria-level', '1');
    expect(within(pallet).getByText('P00000001')).toBeInTheDocument();
    expect(within(pallet).getByText('27 EA · 1 item · 4 LPNs inside')).toBeInTheDocument();
    expect(within(pallet).getByRole('table', { name: 'Stock in P00000001' })).toBeInTheDocument();

    const cartons = within(pallet).getAllByRole('treeitem');
    expect(cartons).toHaveLength(4);
    expect(cartons[0]).toHaveAttribute('aria-level', '2');
    expect(within(cartons[0]).getByText('C00000001')).toBeInTheDocument();
    expect(within(within(cartons[0]).getByRole('table')).getByText('1 CS')).toBeInTheDocument();
  });

  it('collapses and expands containers', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' }));
    const tree = await screen.findByRole('tree');

    await user.click(screen.getByRole('button', { name: 'Collapse all' }));
    expect(within(tree).getAllByRole('treeitem')).toHaveLength(1);
    expect(within(tree).getByRole('treeitem')).toHaveAttribute('aria-expanded', 'false');

    await user.click(within(tree).getByRole('button', { name: /P00000001/ }));
    expect(within(tree).getAllByRole('treeitem')).toHaveLength(5);
  });

  it('opens the location named in the address', async () => {
    renderPage(`/inventory/by-location?location=${RESERVE.id}`);
    expect(await screen.findByRole('tree', { name: 'Containers in DC1-RS-01-01-A-01' })).toBeInTheDocument();
    const row = (await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' })).closest('tr')!;
    expect(row).toHaveAttribute('aria-selected', 'true');
  });

  it('can include empty locations', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: 'Locations' });

    await user.click(screen.getByLabelText('Show empty locations'));

    await waitFor(() =>
      expect(calls.some((u) => u.pathname === '/api/inventory/locations' && u.searchParams.get('occupied') === 'false')).toBe(true),
    );
  });
});
