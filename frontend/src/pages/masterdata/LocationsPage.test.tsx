import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LocationsPage } from './LocationsPage';
import type { Location } from '../../api/locations';

const LOC: Location = {
  id: 'a1',
  locationId: 'DC1-RS-01-01-A-01',
  type: 'STORAGE',
  area: 'DC1',
  zone: 'RS',
  aisle: '01',
  bay: '01',
  level: 'A',
  position: '01',
  width: 48,
  depth: 40,
  height: 60,
  dimensionUom: 'IN',
  maxWeight: 2500,
  maxWeightUom: 'LB',
  maxVolume: 66.667,
  maxVolumeUom: 'CU_FT',
  maxLpns: 1,
  maxSkus: 1,
  maxUnits: null,
  createdAt: '2026-11-19T10:00:00Z',
  updatedAt: '2026-11-19T10:00:00Z',
  version: 3,
};

const TYPES = [
  { type: 'STORAGE', label: 'Storage', requiredIdentifiers: ['area', 'zone', 'aisle', 'bay', 'level', 'position'] },
];

type Handler = (url: URL, init: RequestInit) => Response | undefined;

let calls: { method: string; url: URL; body?: unknown }[];
let override: Handler | undefined;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function page(content: Location[]) {
  return { content, page: 0, size: 25, totalElements: content.length, totalPages: 1 };
}

beforeEach(() => {
  calls = [];
  override = undefined;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit = {}) => {
      const url = new URL(input, 'http://localhost');
      const method = init.method ?? 'GET';
      calls.push({ method, url, body: init.body ? JSON.parse(String(init.body)) : undefined });
      const custom = override?.(url, init);
      if (custom) return custom;
      if (url.pathname === '/api/locations/types') return json(TYPES);
      if (url.pathname === '/api/locations' && method === 'GET') return json(page([LOC]));
      if (url.pathname === '/api/locations' && method === 'POST') return json({ ...LOC, ...JSON.parse(String(init.body)), id: 'new' }, 201);
      if (method === 'PUT') return json({ ...LOC, ...JSON.parse(String(init.body)), version: 4 });
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
    <MemoryRouter initialEntries={['/master-data/locations']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/master-data/locations" element={<LocationsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Locations page', () => {
  it('lists locations with identifiers and dimensions', async () => {
    renderPage();

    const row = (await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' })).closest('tr')!;
    expect(within(row).getByText('Storage')).toBeInTheDocument();
    expect(within(row).getByText('RS')).toBeInTheDocument();
    expect(within(row).getByText('48 × 40 × 60 in')).toBeInTheDocument();
    expect(within(row).getByText('2500 lb')).toBeInTheDocument();
    expect(within(row).getByText('66.667 ft³')).toBeInTheDocument();
    expect(screen.getByText('1 location')).toBeInTheDocument();
  });

  it('shows capacity columns, with a dash where there is no limit', async () => {
    renderPage();
    const row = (await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' })).closest('tr')!;
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual(expect.arrayContaining(['Max weight', 'Max volume', 'Max LPNs', 'Max SKUs', 'Max units']));

    const cells = within(row).getAllByRole('cell');
    const cellUnder = (label: string) => cells[headers.indexOf(label) - 1].textContent;
    expect(cellUnder('Max LPNs')).toBe('1');
    expect(cellUnder('Max SKUs')).toBe('1');
    expect(cellUnder('Max units')).toBe('—');
  });

  it('creates a location, filling the ID from the identifiers', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' });

    await user.click(screen.getByRole('button', { name: 'New location' }));
    const dialog = screen.getByRole('dialog', { name: 'New location' });
    for (const [label, value] of [
      ['Area', 'dc1'],
      ['Zone', 'bk'],
      ['Aisle', '02'],
      ['Bay', '03'],
      ['Level', 'b'],
      ['Position', '01'],
    ]) {
      await user.type(within(dialog).getByLabelText(label), value);
    }
    expect(within(dialog).getByLabelText('Location ID')).toHaveValue('DC1-BK-02-03-B-01');

    await user.type(within(dialog).getByLabelText('Width'), '24');
    await user.type(within(dialog).getByLabelText('Depth'), '24.5');
    await user.type(within(dialog).getByLabelText('Height'), '18');
    await user.click(within(dialog).getByRole('button', { name: 'Create location' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const post = calls.find((c) => c.method === 'POST')!;
    expect(post.body).toMatchObject({
      type: 'STORAGE',
      locationId: 'DC1-BK-02-03-B-01',
      area: 'DC1',
      zone: 'BK',
      level: 'B',
      width: 24,
      depth: 24.5,
      height: 18,
      dimensionUom: 'IN',
      maxWeight: null,
      maxWeightUom: null,
      maxVolume: null,
      maxVolumeUom: null,
      maxLpns: null,
      maxSkus: null,
      maxUnits: null,
    });
    expect(screen.getByRole('status')).toHaveTextContent('Created location DC1-BK-02-03-B-01');
  });

  it('sends capacity limits and offers the physical volume', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New location' }));
    const dialog = screen.getByRole('dialog');
    for (const label of ['Area', 'Zone', 'Aisle', 'Bay', 'Level', 'Position']) {
      await user.type(within(dialog).getByLabelText(label), 'X');
    }
    await user.type(within(dialog).getByLabelText('Width'), '48');
    await user.type(within(dialog).getByLabelText('Depth'), '40');
    await user.type(within(dialog).getByLabelText('Height'), '60');

    expect(within(dialog).getByText(/Physical space from the dimensions: 66.667 ft³/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Use as max volume' }));
    expect(within(dialog).getByLabelText('Max volume')).toHaveValue(66.667);

    await user.type(within(dialog).getByLabelText('Max weight'), '1100');
    await user.selectOptions(within(dialog).getByLabelText('Weight unit'), 'KG');
    await user.type(within(dialog).getByLabelText('Max LPNs'), '2');
    await user.type(within(dialog).getByLabelText('Max SKUs'), '1');
    await user.click(within(dialog).getByRole('button', { name: 'Create location' }));

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true));
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({
      maxWeight: 1100,
      maxWeightUom: 'KG',
      maxVolume: 66.667,
      maxVolumeUom: 'CU_FT',
      maxLpns: 2,
      maxSkus: 1,
      maxUnits: null,
    });
  });

  it('rejects capacity limits that are not positive whole numbers', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New location' }));
    const dialog = screen.getByRole('dialog');

    await user.type(within(dialog).getByLabelText('Max LPNs'), '1.5');
    await user.type(within(dialog).getByLabelText('Max weight'), '0');
    await user.click(within(dialog).getByRole('button', { name: 'Create location' }));

    expect(within(dialog).getByText('Whole number, greater than 0')).toBeInTheDocument();
    expect(within(dialog).getAllByText('Must be greater than 0').length).toBeGreaterThan(0);
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('keeps a hand-typed location ID when identifiers change', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New location' }));
    const dialog = screen.getByRole('dialog');

    await user.type(within(dialog).getByLabelText('Location ID'), 'custom1');
    await user.type(within(dialog).getByLabelText('Area'), 'dc1');

    expect(within(dialog).getByLabelText('Location ID')).toHaveValue('CUSTOM1');
  });

  it('shows validation errors before sending anything', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New location' }));
    const dialog = screen.getByRole('dialog');

    await user.type(within(dialog).getByLabelText('Width'), '0');
    await user.click(within(dialog).getByRole('button', { name: 'Create location' }));

    expect(within(dialog).getByText('Enter a location ID')).toBeInTheDocument();
    expect(within(dialog).getAllByText('Required')).toHaveLength(6);
    expect(within(dialog).getByText('Must be greater than 0')).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('shows server conflicts next to the right field', async () => {
    override = (url, init) =>
      url.pathname === '/api/locations' && init.method === 'POST'
        ? json(
            {
              detail: 'A location with these details already exists',
              errors: { identifiers: 'This Area/Zone/Aisle/Bay/Level/Position combination is already location X' },
            },
            409,
          )
        : undefined;
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New location' }));
    const dialog = screen.getByRole('dialog');
    for (const label of ['Area', 'Zone', 'Aisle', 'Bay', 'Level', 'Position']) {
      await user.type(within(dialog).getByLabelText(label), 'X');
    }
    for (const label of ['Width', 'Depth', 'Height']) {
      await user.type(within(dialog).getByLabelText(label), '1');
    }
    await user.click(within(dialog).getByRole('button', { name: 'Create location' }));

    expect(await within(dialog).findByText(/already location X/)).toBeInTheDocument();
    expect(within(dialog).getByText('A location with these details already exists')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('edits a location and sends its version', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit DC1-RS-01-01-A-01' });
    expect(within(dialog).getByLabelText('Width')).toHaveValue(48);
    expect(within(dialog).getByLabelText('Max weight')).toHaveValue(2500);
    expect(within(dialog).getByLabelText('Max units')).toHaveValue(null);

    await user.clear(within(dialog).getByLabelText('Height'));
    await user.type(within(dialog).getByLabelText('Height'), '72');
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url.pathname).toBe('/api/locations/a1');
    expect(put.body).toMatchObject({
      height: 72,
      version: 3,
      locationId: 'DC1-RS-01-01-A-01',
      maxWeight: 2500,
      maxWeightUom: 'LB',
      maxLpns: 1,
      maxUnits: null,
    });
  });

  it('deletes a location after confirming', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' }));
    const dialog = screen.getByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);

    const confirm = within(dialog).getByRole('group', { name: 'Confirm delete' });
    await user.click(within(confirm).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'DELETE')!.url.pathname).toBe('/api/locations/a1');
    expect(screen.getByRole('status')).toHaveTextContent('Deleted location DC1-RS-01-01-A-01');
  });

  it('sends search and sort to the API', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: 'DC1-RS-01-01-A-01' });

    await user.type(screen.getByRole('searchbox', { name: 'Search locations' }), 'rs-01');
    await waitFor(() => expect(calls.some((c) => c.url.searchParams.get('q') === 'rs-01')).toBe(true));

    await user.click(screen.getByRole('button', { name: 'Zone' }));
    await waitFor(() =>
      expect(calls.some((c) => c.url.searchParams.get('sort') === 'zone' && c.url.searchParams.get('dir') === 'asc')).toBe(true),
    );
  });
});
