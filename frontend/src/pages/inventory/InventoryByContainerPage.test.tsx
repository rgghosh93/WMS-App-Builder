import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InventoryByContainerPage } from './InventoryByContainerPage';
import type { ContainerDetail, ContainerNode, ContainerSummary, InventoryLine } from '../../api/inventory';

const RESERVE = { id: 'l-res', locationId: 'DC1-RS-01-01-B-01', type: 'STORAGE', area: 'DC1', zone: 'RS' };
const PALLET_TYPE = { id: 't-p', containerTypeId: 'PLT-GMA', description: 'GMA pallet', category: 'PALLET' as const };
const CARTON_TYPE = { id: 't-c', containerTypeId: 'CTN-M', description: 'Medium carton', category: 'CARTON' as const };

function summary(id: string, extra: Partial<ContainerSummary>): ContainerSummary {
  return {
    id,
    lpnId: id,
    permanent: false,
    containerType: PALLET_TYPE,
    status: 'IN_USE',
    location: RESERVE,
    path: [],
    totalQuantity: 0,
    itemCount: 1,
    nestedCount: 0,
    lineCount: 0,
    ...extra,
  };
}

const SUMMARIES: ContainerSummary[] = [
  summary('C00000005', { containerType: CARTON_TYPE, path: [{ id: 'P00000003', lpnId: 'P00000003' }], totalQuantity: 4, lineCount: 4 }),
  summary('P00000003', { totalQuantity: 8, nestedCount: 2 }),
  summary('BIN00003', { permanent: true, containerType: { ...CARTON_TYPE, containerTypeId: 'BIN-SHELF', category: 'BIN' }, totalQuantity: 65, lineCount: 2 }),
];

function drill(serial: string): InventoryLine {
  return {
    id: serial,
    item: { id: 'i-drill', itemId: 'SKU-10001', description: 'Cordless drill 18V', packs: [{ uomCode: 'CS', quantity: 4 }] },
    quantity: 1,
    allocatedQuantity: 0,
    lotNumber: null,
    serialNumber: serial,
    expiryDate: null,
    status: 'AVAILABLE',
    receivedAt: '2026-11-16T14:00:00Z',
  };
}

function carton(id: string, serials: string[]): ContainerNode {
  return {
    id,
    lpnId: id,
    permanent: false,
    containerType: CARTON_TYPE,
    status: 'IN_USE',
    totalQuantity: serials.length,
    itemCount: 1,
    lines: serials.map(drill),
    children: [],
  };
}

const PALLET_DETAIL: ContainerDetail = {
  location: RESERVE,
  path: [],
  container: {
    id: 'P00000003',
    lpnId: 'P00000003',
    permanent: false,
    containerType: PALLET_TYPE,
    status: 'IN_USE',
    totalQuantity: 8,
    itemCount: 1,
    lines: [],
    children: [carton('C00000005', ['DR1', 'DR2', 'DR3', 'DR4']), carton('C00000006', ['DR5', 'DR6', 'DR7', 'DR8'])],
  },
};

const CARTON_DETAIL: ContainerDetail = {
  location: RESERVE,
  path: [{ id: 'P00000003', lpnId: 'P00000003' }],
  container: carton('C00000005', ['DR1', 'DR2', 'DR3', 'DR4']),
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
      if (url.pathname === '/api/inventory/containers') {
        return json({ content: SUMMARIES, page: 0, size: 25, totalElements: 3, totalPages: 1 });
      }
      if (url.pathname === '/api/inventory/containers/P00000003') return json(PALLET_DETAIL);
      if (url.pathname === '/api/inventory/containers/C00000005') return json(CARTON_DETAIL);
      return json({ detail: 'Not found' }, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function CurrentUrl() {
  const location = useLocation();
  return <output data-testid="url">{location.pathname + location.search}</output>;
}

function renderPage(path = '/inventory/by-container') {
  return render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/inventory/by-container" element={<InventoryByContainerPage />} />
        <Route path="/inventory/by-location" element={<p>Location view</p>} />
      </Routes>
      <CurrentUrl />
    </MemoryRouter>,
  );
}

describe('Inventory by Container', () => {
  it('lists LPNs with stock, where they are and what they hold', async () => {
    renderPage();
    const table = await screen.findByRole('table', { name: 'Containers' });

    const cartonRow = within(table).getByRole('button', { name: 'C00000005' }).closest('tr')!;
    expect(within(cartonRow).getByText('DC1-RS-01-01-B-01')).toBeInTheDocument();
    expect(within(cartonRow).getByText('in P00000003')).toBeInTheDocument();
    expect(within(cartonRow).getByText('4 EA')).toBeInTheDocument();

    const palletRow = within(table).getByRole('button', { name: 'P00000003' }).closest('tr')!;
    expect(within(palletRow).getByText('1 item · 2 LPNs inside')).toBeInTheDocument();
    expect(within(palletRow).getByText('Pallet · PLT-GMA')).toBeInTheDocument();

    const binRow = within(table).getByRole('button', { name: 'BIN00003' }).closest('tr')!;
    expect(within(binRow).getByText('Bin · BIN-SHELF · Permanent')).toBeInTheDocument();
    expect(screen.getByText('3 LPNs')).toBeInTheDocument();
    expect(calls.find((u) => u.pathname === '/api/inventory/containers')!.searchParams.get('withStock')).toBe('true');
  });

  it('shows a pallet with the cartons inside it', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'P00000003' }));

    const tree = await screen.findByRole('tree', { name: 'Contents of P00000003' });
    const nodes = within(tree).getAllByRole('treeitem');
    expect(nodes).toHaveLength(3);
    expect(within(nodes[1]).getByText('C00000005')).toBeInTheDocument();
    expect(within(nodes[1]).getByText('DR1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'DC1-RS-01-01-B-01' })).toHaveAttribute('href', '/inventory/by-location?location=l-res');
    // The open container has no "open" link of its own; the cartons inside it do.
    expect(within(tree).queryByRole('link', { name: 'Open P00000003 in the container view' })).not.toBeInTheDocument();
    expect(within(tree).getByRole('link', { name: 'Open C00000005 in the container view' })).toBeInTheDocument();
  });

  it('shows where a nested carton is, and jumps to its pallet', async () => {
    const user = userEvent.setup();
    renderPage('/inventory/by-container?container=C00000005');

    expect(await screen.findByRole('tree', { name: 'Contents of C00000005' })).toBeInTheDocument();
    const where = screen.getByText('Where:').closest('p')!;
    expect(within(where).getByRole('link', { name: 'DC1-RS-01-01-B-01' })).toBeInTheDocument();
    await user.click(within(where).getByRole('link', { name: 'P00000003' }));

    await waitFor(() => expect(screen.getByTestId('url')).toHaveTextContent('container=P00000003'));
    expect(await screen.findByRole('tree', { name: 'Contents of P00000003' })).toBeInTheDocument();
  });

  it('filters by category and kind', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: 'Containers' });

    await user.selectOptions(screen.getByLabelText('Category'), 'CARTON');
    await user.selectOptions(screen.getByLabelText('Kind'), 'inventory');

    await waitFor(() =>
      expect(
        calls.some(
          (u) =>
            u.pathname === '/api/inventory/containers' &&
            u.searchParams.get('category') === 'CARTON' &&
            u.searchParams.get('permanent') === 'false',
        ),
      ).toBe(true),
    );
  });
});
