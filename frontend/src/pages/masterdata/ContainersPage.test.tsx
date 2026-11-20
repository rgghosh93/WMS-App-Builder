import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ContainersPage } from './ContainersPage';
import type { Container } from '../../api/containers';
import type { ContainerType } from '../../api/containerTypes';

function type(id: string, code: string, extra: Partial<ContainerType> = {}): ContainerType {
  return {
    id,
    containerTypeId: code,
    description: `${code} description`,
    category: 'TOTE',
    status: 'ACTIVE',
    reusable: true,
    width: 24,
    depth: 16,
    height: 12,
    dimensionUom: 'IN',
    tareWeight: null,
    tareWeightUom: null,
    maxWeight: null,
    maxWeightUom: null,
    maxVolume: null,
    maxVolumeUom: null,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
    ...extra,
  };
}

const TYPES = [
  type('t1', 'TOTE-STD'),
  type('t2', 'BIN-ASRS', { category: 'BIN' }),
  type('t3', 'TOTE-OLD', { status: 'INACTIVE' }),
  type('t4', 'CTN-M', { category: 'CARTON', reusable: false }),
];

const BIN: Container = {
  id: 'c1',
  lpnId: 'BIN00001',
  containerType: { id: 't2', containerTypeId: 'BIN-ASRS', description: 'ASRS bin', category: 'BIN' },
  status: 'IN_USE',
  currentLocation: { id: 'l1', locationId: 'DC1-AS-01-01-A-01' },
  parentContainer: null,
  permanent: true,
  notes: 'Left wall slot',
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-20T10:00:00Z',
  version: 2,
};

const LOCATIONS = [
  { id: 'l1', locationId: 'DC1-AS-01-01-A-01' },
  { id: 'l2', locationId: 'DC1-AS-01-01-A-02' },
];

let calls: { method: string; url: URL; body?: Record<string, unknown> }[];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function page<T>(content: T[]) {
  return { content, page: 0, size: 25, totalElements: content.length, totalPages: 1 };
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
      if (url.pathname === '/api/container-types') return json(page(TYPES));
      if (url.pathname === '/api/locations') {
        const q = (url.searchParams.get('q') ?? '').toLowerCase();
        return json(page(LOCATIONS.filter((l) => l.locationId.toLowerCase().includes(q))));
      }
      if (url.pathname === '/api/containers/range') {
        return json({ created: body!.count, firstLpnId: 'TOTE00001', lastLpnId: 'TOTE00500' }, 201);
      }
      if (method === 'GET') return json(page([BIN]));
      if (method === 'POST') return json({ ...BIN, id: 'new', lpnId: body!.lpnId }, 201);
      if (method === 'PUT') return json({ ...BIN, version: 3 });
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
    <MemoryRouter initialEntries={['/master-data/containers']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/master-data/containers" element={<ContainersPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Containers page', () => {
  it('lists containers with type, status and location', async () => {
    renderPage();
    const row = (await screen.findByRole('button', { name: 'BIN00001' })).closest('tr')!;

    expect(within(row).getByText('BIN-ASRS')).toBeInTheDocument();
    expect(within(row).getByText('Bin')).toBeInTheDocument();
    expect(within(row).getByText('In use')).toBeInTheDocument();
    expect(within(row).getByText('DC1-AS-01-01-A-01')).toBeInTheDocument();
    expect(within(row).getByText('Left wall slot')).toBeInTheDocument();
  });

  it('offers only active reusable types when creating', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'BIN00001' });
    await user.click(screen.getByRole('button', { name: 'New container' }));
    const dialog = screen.getByRole('dialog', { name: 'New container' });

    const typeSelect = within(dialog).getByLabelText('Container type');
    // The placeholder plus the two active reusable types.
    await waitFor(() => expect(within(typeSelect).getAllByRole('option')).toHaveLength(3));
    const options = within(typeSelect).getAllByRole('option').map((o) => o.textContent);
    expect(options.some((o) => o?.startsWith('TOTE-STD'))).toBe(true);
    expect(options.some((o) => o?.startsWith('BIN-ASRS'))).toBe(true);
    expect(options.some((o) => o?.startsWith('TOTE-OLD'))).toBe(false);
    expect(options.some((o) => o?.startsWith('CTN-M'))).toBe(false);
  });

  it('creates a container with a location chosen from the picker', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'BIN00001' });
    await user.click(screen.getByRole('button', { name: 'New container' }));
    const dialog = screen.getByRole('dialog');

    await user.type(within(dialog).getByLabelText('LPN'), 'tote00042');
    await user.selectOptions(within(dialog).getByLabelText('Container type'), 't1');
    await user.type(within(dialog).getByRole('combobox', { name: 'Current location' }), 'a-02');
    await user.click(await within(dialog).findByRole('option', { name: 'DC1-AS-01-01-A-02' }));
    expect(within(dialog).getByRole('combobox', { name: 'Current location' })).toHaveValue('DC1-AS-01-01-A-02');
    await user.type(within(dialog).getByLabelText('Notes'), 'Spare');
    await user.click(within(dialog).getByRole('button', { name: 'Create container' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      lpnId: 'TOTE00042',
      containerTypeId: 't1',
      status: 'AVAILABLE',
      currentLocationId: 'l2',
      notes: 'Spare',
    });
  });

  it('requires an LPN and a type', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New container' }));
    const dialog = screen.getByRole('dialog');

    await user.click(within(dialog).getByRole('button', { name: 'Create container' }));

    expect(within(dialog).getByText('Enter an LPN')).toBeInTheDocument();
    expect(within(dialog).getByText('Choose a container type')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('edits a container, clearing its location', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'BIN00001' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit BIN00001' });

    expect(within(dialog).getByRole('combobox', { name: 'Current location' })).toHaveValue('DC1-AS-01-01-A-01');
    await user.click(within(dialog).getByRole('button', { name: 'Clear location' }));
    await user.selectOptions(within(dialog).getByLabelText('Status'), 'ON_HOLD');
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'PUT')!.body).toMatchObject({
      lpnId: 'BIN00001',
      containerTypeId: 't2',
      status: 'ON_HOLD',
      currentLocationId: null,
      version: 2,
    });
  });

  it('adds a range of containers with a preview', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'BIN00001' });
    await user.click(screen.getByRole('button', { name: 'Add a range' }));
    const dialog = screen.getByRole('dialog', { name: 'Add a range of containers' });

    await user.type(within(dialog).getByLabelText('Prefix'), 'tote');
    await user.type(within(dialog).getByLabelText('How many'), '500');
    expect(within(dialog).getByText('TOTE00001 to TOTE00500')).toBeInTheDocument();
    await waitFor(() => expect(within(within(dialog).getByLabelText('Container type')).getAllByRole('option').length).toBeGreaterThan(1));
    await user.selectOptions(within(dialog).getByLabelText('Container type'), 't1');
    await user.click(within(dialog).getByRole('button', { name: 'Create 500 containers' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.url.pathname === '/api/containers/range')!.body).toEqual({
      prefix: 'TOTE',
      start: 1,
      count: 500,
      digits: 5,
      containerTypeId: 't1',
      status: 'AVAILABLE',
      currentLocationId: null,
      notes: null,
    });
    expect(screen.getByRole('status')).toHaveTextContent('Created 500 containers, TOTE00001 to TOTE00500');
  });

  it('checks the range fits the digits', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Add a range' }));
    const dialog = screen.getByRole('dialog');

    await user.clear(within(dialog).getByLabelText('Digits'));
    await user.type(within(dialog).getByLabelText('Digits'), '2');
    await user.type(within(dialog).getByLabelText('How many'), '150');
    await user.click(within(dialog).getByRole('button', { name: 'Create 150 containers' }));

    expect(within(dialog).getByText('Needs at least 3 to reach 150')).toBeInTheDocument();
    expect(calls.some((c) => c.url.pathname === '/api/containers/range')).toBe(false);
  });

  it('filters by status', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'BIN00001' });

    await user.selectOptions(screen.getByLabelText('Status'), 'RETIRED');

    await waitFor(() =>
      expect(calls.some((c) => c.url.pathname === '/api/containers' && c.url.searchParams.get('status') === 'RETIRED')).toBe(true),
    );
  });
});
