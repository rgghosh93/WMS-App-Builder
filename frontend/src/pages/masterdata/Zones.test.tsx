import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Location } from '../../api/locations';
import type { AddPreview, Zone } from '../../api/zones';
import { ZoneDetailPage } from './ZoneDetailPage';
import { ZonesPage } from './ZonesPage';

const zone = (id: string, extra: Partial<Zone>): Zone => ({
  id, function: 'PICKING', code: id.toUpperCase(), name: `Zone ${id}`, status: 'ACTIVE', priority: null, allocatable: true, locationCount: 2, version: 3, ...extra,
});
const ZONES = [
  zone('first', { function: 'ALLOCATION', priority: 1 }),
  zone('bulk', { function: 'ALLOCATION', allocatable: false, status: 'INACTIVE' }),
  zone('east', {}),
];
const location = (id: string) => ({ id, locationId: `DC1-${id}`, type: 'STORAGE', area: 'DC1', zone: 'BK', aisle: '01', bay: '02', level: 'A', position: null }) as Location;

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let urls: string[];
let preview: AddPreview;
let reply: { status: number; body: unknown } | null;

beforeEach(() => {
  sent = [];
  urls = [];
  reply = null;
  preview = { matching: 12, already: 2, elsewhere: {} };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
      const method = init.method ?? 'GET';
      if (path.endsWith('/preview')) {
        urls.push(`${path} ${init.body}`);
        return reply ? json(reply.body, reply.status) : json(preview);
      }
      if (method !== 'GET') {
        sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
        if (reply) return json(reply.body, reply.status);
        return method === 'DELETE' ? json(null, 204) : json(ZONES[2]);
      }
      urls.push(path);
      if (path.includes('/locations?')) return json({ content: [location('A1'), location('A2')], page: 0, size: 25, totalElements: 2, totalPages: 1 });
      const one = ZONES.find((z) => path === `/api/zones/${z.id}`);
      return one ? json(one) : json(ZONES.filter((z) => !path.includes('function=') || path.endsWith(z.function)));
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/master-data/zones" element={<ZonesPage />} />
        <Route path="/master-data/zones/:id" element={<ZoneDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Zones', () => {
  it('lists zones by what they are for', async () => {
    const user = userEvent.setup();
    renderAt('/master-data/zones');
    const rows = within(await screen.findByRole('table', { name: 'Zones' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('FIRSTZone firstAllocation2Priority 1Active');
    expect(rows[2]).toHaveTextContent('BULKZone bulkAllocation2Kept from ordersInactive');
    await user.selectOptions(screen.getByLabelText('For'), 'Picking');
    await vi.waitFor(() => expect(urls[urls.length - 1]).toBe('/api/zones?function=PICKING'));
    expect(await screen.findByText('1 zone')).toBeInTheDocument();
  });

  it('creates an allocation zone', async () => {
    const user = userEvent.setup();
    renderAt('/master-data/zones');
    await user.click(await screen.findByRole('button', { name: 'New zone' }));
    await user.click(screen.getByRole('button', { name: 'Create zone' }));
    expect(screen.getByText('Enter a code')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Code'), 'fwd');
    await user.type(screen.getByLabelText('Name'), 'Forward pick');
    await user.type(screen.getByLabelText('Priority'), '0');
    await user.click(screen.getByRole('button', { name: 'Create zone' }));
    expect(screen.getByText('A whole number from 1 to 999')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Priority'));
    await user.type(screen.getByLabelText('Priority'), '5');
    await user.click(screen.getByRole('button', { name: 'Create zone' }));
    // It opens, ready for its locations.
    expect(await screen.findByRole('button', { name: 'Add locations' })).toBeInTheDocument();
    expect(sent[0].body).toEqual({ function: 'ALLOCATION', code: 'FWD', name: 'Forward pick', status: 'ACTIVE', priority: 5, allocatable: true });
  });

  it('keeps a zone from orders', async () => {
    const user = userEvent.setup();
    renderAt('/master-data/zones/first');
    await user.click(await screen.findByRole('button', { name: 'Edit zone' }));
    await user.click(screen.getByRole('checkbox', { name: /Orders get stock from here/ }));
    expect(screen.queryByLabelText('Priority')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Saved');
    expect(sent[0]).toMatchObject({ method: 'PUT', path: '/api/zones/first', body: { allocatable: false, priority: null, version: 3 } });
  });

  it('says why a zone cannot be deleted', async () => {
    const user = userEvent.setup();
    renderAt('/master-data/zones/east');
    await user.click(await screen.findByRole('button', { name: 'Edit zone' }));
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    reply = { status: 409, body: { detail: "EAST can't be deleted: used by 1 put-away rule." } };
    await user.click(within(screen.getByRole('group', { name: 'Confirm delete' })).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('used by 1 put-away rule');
  });
});

describe("A zone's locations", () => {
  it('are added by address, moving them from other zones only when told to', async () => {
    const user = userEvent.setup();
    preview = { matching: 12, already: 2, elsewhere: { WEST: 3 } };
    renderAt('/master-data/zones/east');
    await user.click(await screen.findByRole('button', { name: 'Add locations' }));
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByRole('button', { name: 'Add locations' })).toBeDisabled();
    await user.type(dialog.getByLabelText('Area'), 'dc1');
    await user.type(dialog.getByLabelText('Aisle from'), '02');
    expect(await dialog.findByRole('status')).toHaveTextContent('12 storage locations match; 2 already here; in another picking zone: 3 in WEST.');
    expect(dialog.getByRole('button', { name: 'Add 10 locations' })).toBeDisabled();
    await user.click(dialog.getByRole('checkbox', { name: /Move them here from WEST/ }));
    await user.click(dialog.getByRole('button', { name: 'Add 10 locations' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Added 10 locations');
    expect(sent[0]).toMatchObject({ path: '/api/zones/east/locations', body: { filter: { area: 'DC1', aisleFrom: '02' }, move: true } });
  });

  it('are added by ID', async () => {
    const user = userEvent.setup();
    renderAt('/master-data/zones/east');
    await user.click(await screen.findByRole('button', { name: 'Add locations' }));
    const dialog = within(screen.getByRole('dialog'));
    await user.selectOptions(dialog.getByLabelText('Choose by'), 'Location IDs');
    reply = { status: 400, body: { detail: 'NOPE is not a known location', errors: { locationIds: 'NOPE not found' } } };
    await user.type(dialog.getByLabelText('Location IDs'), 'dc1-a1, nope');
    expect(await dialog.findByRole('alert')).toHaveTextContent('NOPE not found');
    reply = null;
    await user.type(dialog.getByLabelText('Location IDs'), '{Backspace}{Backspace}{Backspace}{Backspace}dc1-a2');
    await user.click(await dialog.findByRole('button', { name: 'Add 10 locations' }));
    await vi.waitFor(() => expect(sent[0]?.body).toEqual({ filter: { locationIds: ['DC1-A1', 'DC1-A2'] }, move: false }));
  });

  it('are taken out', async () => {
    const user = userEvent.setup();
    renderAt('/master-data/zones/east');
    await user.click(await screen.findByRole('checkbox', { name: 'Select DC1-A2' }));
    await user.click(screen.getByRole('checkbox', { name: 'Select all on this page' }));
    await user.click(screen.getByRole('button', { name: 'Take 2 out of the zone' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Took 2 locations out');
    expect(sent[0]).toMatchObject({ path: '/api/zones/east/locations/remove', body: { locationIds: ['A2', 'A1'] } });
  });
});
