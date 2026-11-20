import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AllocationsPage } from './AllocationsPage';
import type { AllocationView } from '../../api/orders';

function allocation(id: string, extra: Partial<AllocationView> = {}): AllocationView {
  return {
    id,
    order: { id: 'o1', orderNumber: 'SO001001', customerName: 'Northwind Hardware', status: 'ALLOCATED' },
    lineNumber: 1,
    item: { id: 'i1', itemId: 'SKU-10003', description: 'Safety glasses' },
    quantity: 24,
    location: { id: 'l1', locationId: 'DC1-BK-01-01-A-01' },
    container: { id: 'c1', lpnId: 'BIN00001' },
    lotNumber: null,
    serialNumber: null,
    expiryDate: null,
    inventoryStatus: 'AVAILABLE',
    task: { id: 't1', taskNumber: 'PT000001', detailNumber: 1, status: 'OPEN' },
    createdAt: '2026-11-20T15:00:01Z',
    ...extra,
  };
}

const ROWS: AllocationView[] = [
  allocation('a1'),
  allocation('a2', {
    order: { id: 'o3', orderNumber: 'SO001003', customerName: 'Cascade Pharmacy', status: 'ALLOCATED' },
    item: { id: 'i2', itemId: 'SKU-30001', description: 'Vitamin D3' },
    quantity: 20,
    container: { id: 'c3', lpnId: 'BIN00003' },
    lotNumber: 'VD2609A',
    expiryDate: '2027-09-30',
    task: { id: 't9', taskNumber: 'PT000009', detailNumber: 1, status: 'OPEN' },
  }),
  allocation('a3', {
    order: { id: 'o1', orderNumber: 'SO001001', customerName: 'Northwind Hardware', status: 'ALLOCATED' },
    item: { id: 'i3', itemId: 'SKU-10001', description: 'Cordless drill' },
    quantity: 1,
    location: { id: 'l9', locationId: 'DC1-RS-01-01-B-01' },
    container: null,
    serialNumber: 'DR18-000101',
    inventoryStatus: 'ON_HOLD',
    task: { id: 't3', taskNumber: 'PT000003', detailNumber: 1, status: 'OPEN' },
  }),
];

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
      if (url.pathname === '/api/allocations') {
        return json({ content: ROWS, page: 0, size: 25, totalElements: 3, totalPages: 1, totalAllocatedQuantity: 225 });
      }
      return json({ detail: 'Not found' }, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/outbound/allocations']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/outbound/allocations" element={<AllocationsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Allocations page', () => {
  it('lists each allocation with its order, stock and pick task', async () => {
    renderPage();
    const table = await screen.findByRole('table', { name: 'Allocations' });

    const vitamins = within(table).getByText('SKU-30001').closest('tr')!;
    expect(within(vitamins).getByRole('link', { name: 'SO001003' })).toHaveAttribute('href', '/outbound/orders/o3');
    expect(within(vitamins).getByText('Line 1 · Cascade Pharmacy')).toBeInTheDocument();
    expect(within(vitamins).getByText('20 EA')).toBeInTheDocument();
    expect(within(vitamins).getByRole('link', { name: 'BIN00003' })).toHaveAttribute('href', '/inventory/by-container?container=c3');
    expect(within(vitamins).getByText('VD2609A')).toBeInTheDocument();
    expect(within(vitamins).getByText('PT000009')).toBeInTheDocument();

    const drill = within(table).getByText('SKU-10001').closest('tr')!;
    expect(within(drill).getByText('Loose')).toBeInTheDocument();
    expect(within(drill).getByText('DR18-000101')).toBeInTheDocument();

    expect(screen.getByText('225')).toBeInTheDocument();
    expect(screen.getByText('3 allocations')).toBeInTheDocument();
  });

  it('flags reserved stock that has since been put on hold', async () => {
    renderPage();
    const table = await screen.findByRole('table', { name: 'Allocations' });
    const drill = within(table).getByText('SKU-10001').closest('tr')!;
    expect(within(drill).getByTitle(/put on hold or marked damaged/)).toHaveTextContent('On hold');
  });

  it('searches and filters', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('table', { name: 'Allocations' });

    await user.type(screen.getByRole('searchbox', { name: 'Search allocations' }), 'cascade');
    await user.selectOptions(screen.getByLabelText('Stock status'), 'ON_HOLD');

    await waitFor(() =>
      expect(
        calls.some((u) => u.searchParams.get('q') === 'cascade' && u.searchParams.get('inventoryStatus') === 'ON_HOLD'),
      ).toBe(true),
    );
  });
});
