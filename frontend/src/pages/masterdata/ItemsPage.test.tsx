import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ItemsPage } from './ItemsPage';
import type { Item } from '../../api/items';
import type { Uom } from '../../api/uoms';

function unit(id: string, code: string, kind: Uom['kind'], extra: Partial<Uom> = {}): Uom {
  return {
    id,
    uomCode: code,
    description: code,
    kind,
    status: 'ACTIVE',
    base: false,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
    ...extra,
  };
}

const UNITS: Uom[] = [
  unit('u-ea', 'EA', 'EACH', { description: 'Each', base: true }),
  unit('u-cs', 'CS', 'CASE', { description: 'Case' }),
  unit('u-old', 'OLD', 'OTHER', { status: 'INACTIVE' }),
  unit('u-pl', 'PL', 'PALLET', { description: 'Pallet' }),
];

const DRILL: Item = {
  id: 'i1',
  itemId: 'SKU-10001',
  description: 'Cordless drill 18V',
  status: 'ACTIVE',
  barcode: '0012345100018',
  lotControlled: false,
  serialControlled: true,
  expiryControlled: false,
  shelfLifeDays: null,
  width: 12,
  depth: 4,
  height: 10,
  dimensionUom: 'IN',
  weight: 4.2,
  weightUom: 'LB',
  uoms: [
    {
      id: 'p1',
      uom: { id: 'u-cs', uomCode: 'CS', description: 'Case', kind: 'CASE' },
      quantity: 4,
      barcode: '10012345100015',
      width: 26,
      depth: 13,
      height: 11,
      dimensionUom: 'IN',
      weight: 17.5,
      weightUom: 'LB',
    },
    {
      id: 'p2',
      uom: { id: 'u-pl', uomCode: 'PL', description: 'Pallet', kind: 'PALLET' },
      quantity: 160,
      barcode: null,
      width: null,
      depth: null,
      height: null,
      dimensionUom: null,
      weight: null,
      weightUom: null,
    },
  ],
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-20T10:00:00Z',
  version: 2,
};

const TOTE: Item = {
  ...DRILL,
  id: 'i2',
  itemId: 'SKU-50001',
  description: 'Storage tote',
  status: 'INACTIVE',
  barcode: null,
  serialControlled: false,
  width: null,
  depth: null,
  height: null,
  dimensionUom: null,
  weight: null,
  weightUom: null,
  uoms: [],
};

let calls: { method: string; url: URL; body?: Record<string, unknown> }[];
let override: ((url: URL, init: RequestInit) => Response | undefined) | undefined;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  calls = [];
  override = undefined;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit = {}) => {
      const url = new URL(input, 'http://localhost');
      const method = init.method ?? 'GET';
      const body = init.body ? JSON.parse(String(init.body)) : undefined;
      calls.push({ method, url, body });
      const custom = override?.(url, init);
      if (custom) return custom;
      if (url.pathname === '/api/uoms') return json(UNITS);
      if (method === 'GET') return json({ content: [DRILL, TOTE], page: 0, size: 25, totalElements: 2, totalPages: 1 });
      if (method === 'POST') return json({ ...DRILL, ...body, id: 'new' }, 201);
      if (method === 'PUT') return json({ ...DRILL, ...body, version: 3 });
      if (method === 'DELETE') return new Response(null, { status: 204 });
      return json({}, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/master-data/items']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/master-data/items" element={<ItemsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Items page', () => {
  it('lists items with status, tracking, size and weight', async () => {
    renderPage();

    const drill = (await screen.findByRole('button', { name: 'SKU-10001' })).closest('tr')!;
    expect(within(drill).getByText('Cordless drill 18V')).toBeInTheDocument();
    expect(within(drill).getByText('Active')).toBeInTheDocument();
    expect(within(drill).getByText('Serial')).toBeInTheDocument();
    expect(within(drill).getByText('12 × 4 × 10 in')).toBeInTheDocument();
    expect(within(drill).getByText('4.2 lb')).toBeInTheDocument();
    expect(within(drill).getByText('CS 4 · PL 160')).toBeInTheDocument();

    const tote = screen.getByRole('button', { name: 'SKU-50001' }).closest('tr')!;
    expect(within(tote).getByText('Inactive')).toBeInTheDocument();
    expect(screen.getByText('2 items')).toBeInTheDocument();
  });

  it('creates an item with tracking, dimensions and weight', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New item' }));
    const dialog = screen.getByRole('dialog', { name: 'New item' });

    await user.type(within(dialog).getByLabelText('Item ID'), 'sku-9');
    await user.type(within(dialog).getByLabelText('Description'), 'Coffee beans');
    await user.type(within(dialog).getByLabelText(/Barcode/), '0099');
    await user.click(within(dialog).getByLabelText(/Lot number/));
    expect(within(dialog).queryByLabelText('Shelf life (days)')).not.toBeInTheDocument();
    await user.click(within(dialog).getByLabelText(/Expiry date/));
    await user.type(within(dialog).getByLabelText('Shelf life (days)'), '180');
    await user.type(within(dialog).getByLabelText('Width'), '9');
    await user.type(within(dialog).getByLabelText('Depth'), '5');
    await user.type(within(dialog).getByLabelText('Height'), '4');
    await user.type(within(dialog).getByLabelText('Weight'), '2.125');
    await user.selectOptions(within(dialog).getAllByLabelText('Unit')[1], 'KG');
    await user.click(within(dialog).getByRole('button', { name: 'Create item' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      itemId: 'SKU-9',
      description: 'Coffee beans',
      status: 'ACTIVE',
      barcode: '0099',
      lotControlled: true,
      serialControlled: false,
      expiryControlled: true,
      shelfLifeDays: 180,
      width: 9,
      depth: 5,
      height: 4,
      dimensionUom: 'IN',
      weight: 2.125,
      weightUom: 'KG',
      uoms: [],
    });
    expect(screen.getByRole('status')).toHaveTextContent('Created item SKU-9');
  });

  it('sends empty optional groups as null', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New item' }));
    const dialog = screen.getByRole('dialog');

    await user.type(within(dialog).getByLabelText('Item ID'), 'SKU-10');
    await user.type(within(dialog).getByLabelText('Description'), 'Plain item');
    await user.click(within(dialog).getByRole('button', { name: 'Create item' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({
      barcode: null,
      shelfLifeDays: null,
      width: null,
      dimensionUom: null,
      weight: null,
      weightUom: null,
    });
  });

  it('requires all three dimensions together', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New item' }));
    const dialog = screen.getByRole('dialog');

    await user.type(within(dialog).getByLabelText('Item ID'), 'SKU-11');
    await user.type(within(dialog).getByLabelText('Description'), 'Box');
    await user.type(within(dialog).getByLabelText('Width'), '5');
    await user.click(within(dialog).getByRole('button', { name: 'Create item' }));

    expect(within(dialog).getAllByText('Enter all three, or none')).toHaveLength(2);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('shows a duplicate barcode from the server on the barcode field', async () => {
    override = (_url, init) =>
      init.method === 'POST'
        ? json({ detail: 'An item with these details already exists', errors: { barcode: 'already assigned to item SKU-10001' } }, 409)
        : undefined;
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New item' }));
    const dialog = screen.getByRole('dialog');

    await user.type(within(dialog).getByLabelText('Item ID'), 'SKU-12');
    await user.type(within(dialog).getByLabelText('Description'), 'Copy');
    await user.type(within(dialog).getByLabelText(/Barcode/), '0012345100018');
    await user.click(within(dialog).getByRole('button', { name: 'Create item' }));

    expect(await within(dialog).findByText('Already assigned to item SKU-10001')).toBeInTheDocument();
  });

  it('edits an item and can mark it inactive', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'SKU-10001' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit SKU-10001' });

    expect(within(dialog).getByLabelText(/Serial number/)).toBeChecked();
    await user.selectOptions(within(dialog).getByLabelText('Status'), 'INACTIVE');
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url.pathname).toBe('/api/items/i1');
    expect(put.body).toMatchObject({ status: 'INACTIVE', version: 2, serialControlled: true, width: 12, weightUom: 'LB' });
  });

  it('deletes an item after confirming', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'SKU-50001' }));
    const dialog = screen.getByRole('dialog');

    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await user.click(within(within(dialog).getByRole('group', { name: 'Confirm delete' })).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'DELETE')!.url.pathname).toBe('/api/items/i2');
  });

  it('filters by status', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'SKU-10001' });

    await user.selectOptions(screen.getByLabelText('Status'), 'INACTIVE');

    await waitFor(() => expect(calls.some((c) => c.url.searchParams.get('status') === 'INACTIVE')).toBe(true));
  });
});
