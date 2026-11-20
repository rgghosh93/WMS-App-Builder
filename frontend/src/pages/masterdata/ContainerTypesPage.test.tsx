import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ContainerTypesPage } from './ContainerTypesPage';
import type { ContainerType } from '../../api/containerTypes';

const PALLET: ContainerType = {
  id: 'c1',
  containerTypeId: 'PLT-GMA',
  description: 'GMA pallet, 48 x 40, wood',
  category: 'PALLET',
  status: 'ACTIVE',
  reusable: true,
  width: 48,
  depth: 40,
  height: 60,
  dimensionUom: 'IN',
  tareWeight: 40,
  tareWeightUom: 'LB',
  maxWeight: 2500,
  maxWeightUom: 'LB',
  maxVolume: null,
  maxVolumeUom: null,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-20T10:00:00Z',
  version: 4,
};

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
      if (method === 'GET') return json({ content: [PALLET], page: 0, size: 25, totalElements: 1, totalPages: 1 });
      if (method === 'POST') return json({ ...PALLET, ...body, id: 'new' }, 201);
      if (method === 'PUT') return json({ ...PALLET, ...body, version: 5 });
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
    <MemoryRouter initialEntries={['/master-data/container-types']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/master-data/container-types" element={<ContainerTypesPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Container Types page', () => {
  it('lists container types with category, size and weights', async () => {
    renderPage();
    const row = (await screen.findByRole('button', { name: 'PLT-GMA' })).closest('tr')!;

    expect(within(row).getByText('Pallet')).toBeInTheDocument();
    expect(within(row).getByText('Active')).toBeInTheDocument();
    expect(within(row).getByText('Yes')).toBeInTheDocument();
    expect(within(row).getByText('48 × 40 × 60 in')).toBeInTheDocument();
    expect(within(row).getByText('40 lb')).toBeInTheDocument();
    expect(within(row).getByText('2500 lb')).toBeInTheDocument();
    expect(screen.getByText('1 container type')).toBeInTheDocument();
  });

  it('creates a carton, defaulting reusable from the category', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New container type' }));
    const dialog = screen.getByRole('dialog', { name: 'New container type' });

    expect(within(dialog).getByLabelText(/Reusable/)).toBeChecked();
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'CARTON');
    expect(within(dialog).getByLabelText(/Reusable/)).not.toBeChecked();

    await user.type(within(dialog).getByLabelText('Container type ID'), 'ctn-m');
    await user.type(within(dialog).getByLabelText('Description'), 'Medium carton');
    await user.type(within(dialog).getByLabelText('Width'), '18');
    await user.type(within(dialog).getByLabelText('Depth'), '14');
    await user.type(within(dialog).getByLabelText('Height'), '12');
    expect(within(dialog).getByText(/Outer volume from the dimensions: 1.75 ft³/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Tare weight'), '0.9');
    await user.type(within(dialog).getByLabelText('Max load'), '50');
    await user.click(within(dialog).getByRole('button', { name: 'Use as max volume' }));
    await user.click(within(dialog).getByRole('button', { name: 'Create container type' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      containerTypeId: 'CTN-M',
      description: 'Medium carton',
      category: 'CARTON',
      status: 'ACTIVE',
      reusable: false,
      width: 18,
      depth: 14,
      height: 12,
      dimensionUom: 'IN',
      tareWeight: 0.9,
      tareWeightUom: 'LB',
      maxWeight: 50,
      maxWeightUom: 'LB',
      maxVolume: 1.75,
      maxVolumeUom: 'CU_FT',
    });
    expect(screen.getByRole('status')).toHaveTextContent('Created container type CTN-M');
  });

  it('keeps a hand-set reusable choice when the category changes', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New container type' }));
    const dialog = screen.getByRole('dialog');

    await user.click(within(dialog).getByLabelText(/Reusable/));
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'TOTE');

    expect(within(dialog).getByLabelText(/Reusable/)).not.toBeChecked();
  });

  it('requires ID, description and outer dimensions', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New container type' }));
    const dialog = screen.getByRole('dialog');

    await user.click(within(dialog).getByRole('button', { name: 'Create container type' }));

    expect(within(dialog).getByText('Enter a container type ID')).toBeInTheDocument();
    expect(within(dialog).getByText('Enter a description')).toBeInTheDocument();
    expect(within(dialog).getAllByText('Required')).toHaveLength(3);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('edits a container type and sends its version', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'PLT-GMA' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit PLT-GMA' });

    await user.selectOptions(within(dialog).getByLabelText('Status'), 'INACTIVE');
    await user.clear(within(dialog).getByLabelText('Tare weight'));
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url.pathname).toBe('/api/container-types/c1');
    expect(put.body).toMatchObject({ status: 'INACTIVE', version: 4, tareWeight: null, tareWeightUom: null, maxWeight: 2500 });
  });

  it('deletes after confirming', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'PLT-GMA' }));
    const dialog = screen.getByRole('dialog');

    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await user.click(within(within(dialog).getByRole('group', { name: 'Confirm delete' })).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE')).toBe(true));
    expect(calls.find((c) => c.method === 'DELETE')!.url.pathname).toBe('/api/container-types/c1');
  });

  it('filters by category', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'PLT-GMA' });

    await user.selectOptions(screen.getByLabelText('Category'), 'TOTE');

    await waitFor(() => expect(calls.some((c) => c.url.searchParams.get('category') === 'TOTE')).toBe(true));
  });
});
