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
      if (method === 'GET') return json({ content: [DRILL], page: 0, size: 25, totalElements: 1, totalPages: 1 });
      if (method === 'POST') return json({ ...DRILL, id: 'new', itemId: body!.itemId }, 201);
      if (method === 'PUT') return json({ ...DRILL, version: 3 });
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

async function openNewItem(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'New item' }));
  const dialog = screen.getByRole('dialog', { name: 'New item' });
  // Wait for the units to load so "Add packaging level" can pick one.
  await waitFor(() => expect(within(dialog).getByText('Handled in EA only.')).toBeInTheDocument());
  await user.type(within(dialog).getByLabelText('Item ID'), 'SKU-77');
  await user.type(within(dialog).getByLabelText('Description'), 'Paper towels');
  return dialog;
}

describe('Item packaging', () => {
  it('adds packaging levels to a new item', async () => {
    const user = userEvent.setup();
    renderPage();
    const dialog = await openNewItem(user);

    await user.click(within(dialog).getByRole('button', { name: 'Add packaging level' }));
    const first = within(dialog).getByRole('listitem', { name: 'Packaging level 1' });
    expect(within(first).getByLabelText('Unit')).toHaveValue('u-cs');
    await user.type(within(first).getByLabelText('EA per unit'), '12');
    await user.type(within(first).getByLabelText('Barcode'), '10099');
    await user.type(within(first).getByLabelText('Weight'), '8.5');
    expect(within(first).getByText('1 CS = 12 EA')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Add packaging level' }));
    const second = within(dialog).getByRole('listitem', { name: 'Packaging level 2' });
    expect(within(second).getByLabelText('Unit')).toHaveValue('u-pl');
    const unitOptions = within(within(second).getByLabelText('Unit'))
      .getAllByRole('option')
      .map((o) => o.textContent);
    expect(unitOptions.some((o) => o?.startsWith('EA'))).toBe(false);
    expect(unitOptions.some((o) => o?.startsWith('OLD'))).toBe(false);
    await user.type(within(second).getByLabelText('EA per unit'), '600');

    await user.click(within(dialog).getByRole('button', { name: 'Create item' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'POST')!.body!.uoms).toEqual([
      { uomId: 'u-cs', quantity: 12, barcode: '10099', width: null, depth: null, height: null, dimensionUom: null, weight: 8.5, weightUom: 'LB' },
      { uomId: 'u-pl', quantity: 600, barcode: null, width: null, depth: null, height: null, dimensionUom: null, weight: null, weightUom: null },
    ]);
  });

  it('checks packaging levels before saving', async () => {
    const user = userEvent.setup();
    renderPage();
    const dialog = await openNewItem(user);

    await user.click(within(dialog).getByRole('button', { name: 'Add packaging level' }));
    await user.click(within(dialog).getByRole('button', { name: 'Add packaging level' }));
    const first = within(dialog).getByRole('listitem', { name: 'Packaging level 1' });
    const second = within(dialog).getByRole('listitem', { name: 'Packaging level 2' });
    await user.type(within(first).getByLabelText('EA per unit'), '1');
    await user.selectOptions(within(second).getByLabelText('Unit'), 'u-cs');
    await user.type(within(second).getByLabelText('EA per unit'), '6');
    await user.type(within(second).getByLabelText('Width'), '10');
    await user.click(within(dialog).getByRole('button', { name: 'Create item' }));

    expect(within(first).getByText('At least 2')).toBeInTheDocument();
    expect(within(second).getByText('Already listed')).toBeInTheDocument();
    expect(within(second).getAllByText('Enter all three')).toHaveLength(2);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('edits existing packaging and can remove a level', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'SKU-10001' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit SKU-10001' });

    const caseLevel = within(dialog).getByRole('listitem', { name: 'Packaging level 1' });
    await waitFor(() => expect(within(caseLevel).getByText('1 CS = 4 EA')).toBeInTheDocument());
    expect(within(caseLevel).getByLabelText('Barcode')).toHaveValue('10012345100015');
    await user.click(within(dialog).getByRole('button', { name: 'Remove packaging level 2' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'PUT')!.body!.uoms).toEqual([
      { uomId: 'u-cs', quantity: 4, barcode: '10012345100015', width: 26, depth: 13, height: 11, dimensionUom: 'IN', weight: 17.5, weightUom: 'LB' },
    ]);
  });

  it("shows the server's packaging errors on the right level", async () => {
    override = (url, init) =>
      url.pathname === '/api/items' && init.method === 'POST'
        ? json({ detail: 'An item with these details already exists', errors: { 'uoms[0].barcode': 'already assigned to item SKU-1' } }, 409)
        : undefined;
    const user = userEvent.setup();
    renderPage();
    const dialog = await openNewItem(user);

    await user.click(within(dialog).getByRole('button', { name: 'Add packaging level' }));
    const first = within(dialog).getByRole('listitem', { name: 'Packaging level 1' });
    await user.type(within(first).getByLabelText('EA per unit'), '6');
    await user.type(within(first).getByLabelText('Barcode'), '0001');
    await user.click(within(dialog).getByRole('button', { name: 'Create item' }));

    expect(await within(first).findByText('Already assigned to item SKU-1')).toBeInTheDocument();
  });
});
