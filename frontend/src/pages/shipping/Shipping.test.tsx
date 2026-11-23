import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Carrier } from '../../api/carriers';
import type { Carton } from '../../api/packing';
import type { DoorState, Manifest, Shipment } from '../../api/shipping';
import { CarriersPage } from '../masterdata/CarriersPage';
import { RfApp } from '../rf/RfApp';
import { ManifestDetailPage } from './ManifestDetailPage';
import { ManifestsPage } from './ManifestsPage';
import { LabelsPage, ManifestPrintPage, PackingSlipPage } from './PrintPages';
import { ShipmentDetailPage } from './ShipmentDetailPage';
import { ShipmentsPage } from './ShipmentsPage';

const PARCEL: Carrier = {
  id: 'c1',
  carrierCode: 'PARCEL',
  name: 'Parcel Co',
  mode: 'PARCEL',
  trackingRequired: true,
  status: 'ACTIVE',
  services: [
    { id: 'sv1', serviceCode: 'GROUND', name: 'Ground', status: 'ACTIVE' },
    { id: 'sv2', serviceCode: 'OLD', name: 'Old', status: 'INACTIVE' },
  ],
  version: 3,
};
const ADDRESS = { name: 'Acme Retail', line1: '1 Main St', line2: null, city: 'Springfield', state: 'IL', postalCode: '62701', country: 'US' };

function carton(lpnId: string, extra: Partial<Carton> = {}): Carton {
  return {
    id: `k-${lpnId}`,
    container: { id: `x-${lpnId}`, lpnId },
    containerType: 'CTN-M',
    order: { id: 'o1', orderNumber: 'SO001', customerName: 'Acme', status: 'PACKED' },
    status: 'CLOSED',
    weight: 4.25,
    weightUom: 'LB',
    totalQuantity: 10,
    contents: [
      { detailId: 'd1', lineNumber: 1, item: { id: 'i1', itemId: 'SKU-BAR', description: 'Protein bar' }, lotNumber: 'L1', serialNumber: null, quantity: 10 },
    ],
    closedAt: '2026-11-21T15:00:00Z',
    closedBy: 'Ann Lee',
    trackingNumber: null,
    loadedAt: null,
    ...extra,
  };
}

function shipment(extra: Partial<Shipment> = {}): Shipment {
  const cartons = extra.cartons ?? [carton('BOX1')];
  return {
    id: 's1',
    shipmentNumber: 'SH000001',
    status: 'OPEN',
    order: { id: 'o1', orderNumber: 'SO001', customerName: 'Acme', status: 'PACKED' },
    shipTo: null,
    carrier: null,
    service: null,
    manifest: null,
    loadedCount: cartons.filter((c) => c.loadedAt).length,
    totalQuantity: cartons.reduce((s, c) => s + c.totalQuantity, 0),
    unpackedQuantity: 0,
    missing: ['Enter the ship-to address', 'Choose a carrier service'],
    cartons,
    createdAt: '2026-11-21T15:00:00Z',
    shippedAt: null,
    version: 7,
    ...extra,
  };
}

const READY: Partial<Shipment> = {
  shipTo: ADDRESS,
  carrier: { id: 'c1', carrierCode: 'PARCEL', name: 'Parcel Co', mode: 'PARCEL', trackingRequired: true },
  service: { id: 'sv1', serviceCode: 'GROUND', name: 'Ground' },
  missing: [],
};

function manifest(shipments: Shipment[], extra: Partial<Manifest> = {}): Manifest {
  const loaded = shipments.flatMap((s) => s.cartons).filter((c) => c.loadedAt);
  return {
    id: 'm1',
    manifestNumber: 'MF000001',
    status: 'OPEN',
    carrier: READY.carrier!,
    door: { id: 'l1', locationId: 'DC1-DOCK-01' },
    trailerNumber: null,
    sealNumber: null,
    cartonCount: loaded.length,
    totalQuantity: loaded.reduce((s, c) => s + c.totalQuantity, 0),
    shipments,
    createdBy: 'Ann Lee',
    createdAt: '2026-11-21T16:00:00Z',
    closedBy: null,
    closedAt: null,
    ...extra,
  };
}

const LOADED = { loadedAt: '2026-11-21T16:05:00Z' };
const DOOR = { id: 'l1', locationId: 'DC1-DOCK-01' };
const empty: DoorState = { door: DOOR, manifest: null, waiting: [] };

type Reply = { status?: number; body: unknown };
let calls: { method: string; path: string; body?: Record<string, unknown> }[];
let replies: Record<string, Reply[]>;
let current: Shipment;
let currentManifest: Manifest;
let doorState: DoorState;

beforeEach(() => {
  calls = [];
  replies = {};
  current = shipment();
  currentManifest = manifest([]);
  doorState = empty;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET';
      calls.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : undefined });
      const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
      const reply = replies[`${method} ${path}`]?.shift();
      if (reply) return json(reply.body, reply.status);
      if (method === 'GET') {
        if (path === '/api/rf/session') return json({ id: 'u1', userCode: 'ANN', displayName: 'Ann Lee' });
        if (path === '/api/carriers' || path === '/api/rf/loading/carriers') return json([PARCEL]);
        if (path === '/api/rf/loading/doors') return json([{ ...DOOR, manifestNumber: null, carrierName: null }]);
        if (path.startsWith('/api/rf/loading/doors/')) return json(doorState);
        if (path === '/api/shipments/s1') return json(current);
        if (path === '/api/manifests/m1') return json(currentManifest);
        if (path.startsWith('/api/shipments?')) return json({ content: [current], page: 0, size: 25, totalElements: 1, totalPages: 1 });
        if (path.startsWith('/api/manifests?')) return json({ content: [currentManifest], page: 0, size: 25, totalElements: 1, totalPages: 1 });
      }
      return json({ detail: 'Not found' }, 404);
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
        <Route path="/master-data/carriers" element={<CarriersPage />} />
        <Route path="/pack-ship/shipments" element={<ShipmentsPage />} />
        <Route path="/pack-ship/shipments/:id" element={<ShipmentDetailPage />} />
        <Route path="/pack-ship/manifests" element={<ManifestsPage />} />
        <Route path="/pack-ship/manifests/:id" element={<ManifestDetailPage />} />
        <Route path="/print/shipments/:id/labels" element={<LabelsPage />} />
        <Route path="/print/shipments/:id/packing-slip" element={<PackingSlipPage />} />
        <Route path="/print/manifests/:id" element={<ManifestPrintPage />} />
        <Route path="/rf/*" element={<RfApp />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function scan(user: UserEvent, label: string, value: string) {
  await user.type(await screen.findByLabelText(label), `${value}{Enter}`);
}

const sent = (method: string, path: string) => calls.filter((c) => c.method === method && c.path === path).map((c) => c.body);

describe('Carriers', () => {
  it('creates a carrier with services after checking it', async () => {
    const user = userEvent.setup();
    replies['POST /api/carriers'] = [{ status: 201, body: { ...PARCEL, carrierCode: 'NEW' } }];
    renderAt('/master-data/carriers');
    expect(await screen.findByText('Old (inactive)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'New carrier' }));
    const dialog = screen.getByRole('dialog', { name: 'New carrier' });
    await user.click(within(dialog).getByRole('button', { name: 'Create carrier' }));
    expect(within(dialog).getAllByText('Enter a code')).toHaveLength(2);
    const [code, serviceCode] = within(dialog).getAllByLabelText('Code');
    const [name, serviceName] = within(dialog).getAllByLabelText('Name');
    await user.type(code, 'new');
    await user.type(name, 'New Co');
    await user.type(serviceCode, 'std');
    await user.type(serviceName, 'Standard');
    await user.click(within(dialog).getByRole('button', { name: 'Create carrier' }));
    expect(await screen.findByText('Created carrier NEW')).toBeInTheDocument();
    expect(sent('POST', '/api/carriers')[0]).toMatchObject({
      carrierCode: 'NEW',
      trackingRequired: true,
      services: [{ id: null, serviceCode: 'STD', name: 'Standard', status: 'ACTIVE' }],
    });
  });

  it('edits services and shows why one cannot be removed', async () => {
    const user = userEvent.setup();
    replies['PUT /api/carriers/c1'] = [{ status: 409, body: { detail: "Service GROUND can't be removed: shipments use it." } }];
    renderAt('/master-data/carriers');
    await user.click(await screen.findByRole('button', { name: 'PARCEL' }));
    await user.click(screen.getByRole('button', { name: 'Remove service 1' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveTextContent("GROUND can't be removed");
    expect(sent('PUT', '/api/carriers/c1')[0]).toMatchObject({ version: 3, services: [{ id: 'sv2' }] });
  });
});

describe('Shipments', () => {
  it('lists shipments and filters them', async () => {
    const user = userEvent.setup();
    renderAt('/pack-ship/shipments');
    const table = await screen.findByRole('table', { name: 'Shipments' });
    expect(within(table).getByRole('link', { name: 'SH000001' })).toHaveAttribute('href', '/pack-ship/shipments/s1');
    expect(within(table).getByText('Not ready')).toBeInTheDocument();
    await screen.findByRole('option', { name: 'Parcel Co' });
    await user.selectOptions(screen.getByLabelText('Carrier'), 'c1');
    await user.selectOptions(screen.getByLabelText('Status'), 'SHIPPED');
    await waitFor(() => expect(calls.some((c) => c.path.includes('status=SHIPPED') && c.path.includes('carrierId=c1'))).toBe(true));
  });

  it('gets a shipment ready: address, carrier service and tracking', async () => {
    const user = userEvent.setup();
    const withService = shipment({ ...READY, missing: ['Enter tracking numbers (1 carton)'], version: 8 });
    replies['PUT /api/orders/o1/ship-to'] = [{ body: ADDRESS }];
    replies['PUT /api/shipments/s1/service'] = [{ body: withService }];
    replies['PUT /api/shipments/s1/cartons/k-BOX1/tracking'] = [
      { status: 409, body: { detail: 'That tracking number is already used' } },
      { body: shipment({ ...READY, cartons: [carton('BOX1', { trackingNumber: 'TRK-2' })] }) },
    ];
    renderAt('/pack-ship/shipments/s1');
    const needed = await screen.findByRole('group', { name: 'Still needed' });
    expect(within(needed).getAllByRole('listitem')).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: 'Enter address' }));
    const dialog = screen.getByRole('dialog', { name: 'Ship SO001 to' });
    await user.click(within(dialog).getByRole('button', { name: 'Save address' }));
    expect(within(dialog).getByText('Enter a street address')).toBeInTheDocument();
    expect(sent('PUT', '/api/orders/o1/ship-to')).toEqual([]);
    await user.type(within(dialog).getByLabelText('Name'), 'Acme Retail');
    await user.type(within(dialog).getByLabelText('Street address'), '1 Main St');
    await user.type(within(dialog).getByLabelText('City'), 'Springfield');
    await user.type(within(dialog).getByLabelText('Postal code'), '62701');
    current = shipment({ shipTo: ADDRESS, missing: ['Choose a carrier service'] });
    await user.click(within(dialog).getByRole('button', { name: 'Save address' }));
    expect(await screen.findByText('Springfield, IL 62701')).toBeInTheDocument();
    expect(sent('PUT', '/api/orders/o1/ship-to')[0]).toEqual({ ...ADDRESS, state: null });

    // Inactive services aren't offered.
    const select = screen.getByLabelText('Carrier service');
    expect(await within(select).findByRole('option', { name: 'Parcel Co · Ground' })).toBeInTheDocument();
    expect(within(select).queryByRole('option', { name: /Old/ })).not.toBeInTheDocument();
    await user.selectOptions(select, 'sv1');
    expect(await screen.findByText('Enter tracking numbers (1 carton)')).toBeInTheDocument();
    expect(sent('PUT', '/api/shipments/s1/service')).toEqual([{ carrierServiceId: 'sv1', version: 7 }]);

    current = withService;
    await user.type(screen.getByLabelText('Tracking number for BOX1'), 'trk-1{Enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('That tracking number is already used');
    await user.clear(screen.getByLabelText('Tracking number for BOX1'));
    await user.type(screen.getByLabelText('Tracking number for BOX1'), 'trk-2{Enter}');
    expect(await screen.findByText('Ready to load at a dock door')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Still needed' })).not.toBeInTheDocument();
  });

  it('says what will not go with it, and locks once loading', async () => {
    current = shipment({ ...READY, status: 'LOADING', unpackedQuantity: 3, cartons: [carton('BOX1', LOADED)], manifest: { id: 'm1', manifestNumber: 'MF000001' } });
    renderAt('/pack-ship/shipments/s1');
    expect(await screen.findByText('1 of 1 cartons loaded')).toBeInTheDocument();
    expect(screen.getByText(/3 EA of SO001 are not packed/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Carrier service')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /address/ })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Tracking number for/)).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'MF000001' })).toHaveAttribute('href', '/pack-ship/manifests/m1');
  });
});

describe('Manifests and documents', () => {
  it('lists manifests and shows one', async () => {
    currentManifest = manifest([shipment({ ...READY, status: 'SHIPPED', cartons: [carton('BOX1', { ...LOADED, trackingNumber: 'TRK-1' })] })], {
      status: 'CLOSED',
      trailerNumber: 'TR-9',
      closedAt: '2026-11-21T17:00:00Z',
      closedBy: 'Bob Ray',
    });
    renderAt('/pack-ship/manifests');
    const table = await screen.findByRole('table', { name: 'Manifests' });
    expect(within(table).getByText('TR-9')).toBeInTheDocument();
    await userEvent.setup().click(within(table).getByRole('link', { name: 'MF000001' }));
    expect(await screen.findByRole('heading', { name: /MF000001/ })).toBeInTheDocument();
    expect(screen.getByText('Bob Ray')).toBeInTheDocument();
    expect(screen.getByText(/TRK-1/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Print manifest' })).toHaveAttribute('href', '/print/manifests/m1');
  });

  it('prints a label for each carton', async () => {
    current = shipment({ ...READY, cartons: [carton('BOX1', { trackingNumber: 'TRK-1' }), carton('BOX2')] });
    const print = vi.fn();
    vi.stubGlobal('print', print);
    renderAt('/print/shipments/s1/labels');
    const label = await screen.findByRole('article', { name: 'Label BOX2' });
    expect(label).toHaveTextContent('Acme Retail');
    expect(label).toHaveTextContent('2 of 2');
    expect(label).toHaveTextContent('Parcel Co Ground');
    expect(within(label).getByRole('img', { name: 'Barcode BOX2' })).toBeInTheDocument();
    expect(screen.getByRole('article', { name: 'Label BOX1' })).toHaveTextContent('TRK-1');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Print' }));
    expect(print).toHaveBeenCalled();
  });

  it('adds up each line on the packing slip', async () => {
    current = shipment({ ...READY, cartons: [carton('BOX1'), carton('BOX2')], unpackedQuantity: 5 });
    renderAt('/print/shipments/s1/packing-slip');
    expect(await screen.findByRole('heading', { name: 'Packing slip' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '20 EA' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: 'L1' })).toBeInTheDocument();
    expect(screen.getByText('5 EA of this order will follow separately.')).toBeInTheDocument();
  });

  it('prints only what was loaded on the manifest', async () => {
    currentManifest = manifest([shipment({ ...READY, cartons: [carton('BOX1', LOADED), carton('BOX2')] })]);
    renderAt('/print/manifests/m1');
    expect(await screen.findByRole('cell', { name: 'BOX1' })).toBeInTheDocument();
    expect(screen.queryByRole('cell', { name: 'BOX2' })).not.toBeInTheDocument();
  });
});

describe('Loading', () => {
  const BASE = '/api/rf/loading';
  const waiting: DoorState = { door: DOOR, manifest: manifest([]), waiting: [shipment({ ...READY, cartons: [carton('BOX1'), carton('BOX2')] })] };
  const one: DoorState = {
    door: DOOR,
    manifest: manifest([shipment({ ...READY, status: 'LOADING', cartons: [carton('BOX1', LOADED), carton('BOX2')] })]),
    waiting: [],
  };
  const both: DoorState = {
    door: DOOR,
    manifest: manifest([shipment({ ...READY, status: 'LOADING', cartons: [carton('BOX1', LOADED), carton('BOX2', LOADED)] })]),
    waiting: [],
  };

  it('opens a manifest, loads every carton and ships', async () => {
    const user = userEvent.setup();
    replies[`GET ${BASE}/doors/NOPE`] = [{ status: 400, body: { detail: 'NOPE is not a dock door' } }];
    replies[`POST ${BASE}/doors/DC1-DOCK-01/manifest`] = [{ body: waiting }];
    replies[`POST ${BASE}/manifests/m1/load`] = [
      { status: 400, body: { detail: 'BOX9 is not a shipping carton' } },
      { body: one },
      { body: both },
    ];
    replies[`POST ${BASE}/manifests/m1/close`] = [{ body: { ...both.manifest, status: 'CLOSED' } }];
    renderAt('/rf/loading');
    await scan(user, 'Dock door', 'NOPE');
    expect(await screen.findByRole('alert')).toHaveTextContent('not a dock door');
    await scan(user, 'Dock door', 'DC1-DOCK-01');
    await user.click(await screen.findByRole('button', { name: 'Parcel Co' }));
    expect(await screen.findByText('0 cartons loaded · 2 to load')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close manifest' })).not.toBeInTheDocument();

    await scan(user, 'Scan a carton to load', 'BOX9');
    expect(await screen.findByRole('alert')).toHaveTextContent('not a shipping carton');
    await scan(user, 'Scan a carton to load', 'box1');
    expect(await screen.findByText('BOX1 loaded.')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'To load' })).getByText('BOX2')).toBeInTheDocument();
    await scan(user, 'Scan a carton to load', 'box2');
    await user.click(await screen.findByRole('button', { name: 'Close manifest' }));
    await user.type(screen.getByLabelText('Trailer (optional)'), 'TR-9');
    await user.click(screen.getByRole('button', { name: 'Close manifest and ship' }));
    expect(await screen.findByText('MF000001 is closed: 2 cartons shipped with Parcel Co.')).toBeInTheDocument();
    expect(sent('POST', `${BASE}/manifests/m1/close`)).toEqual([{ trailerNumber: 'TR-9', sealNumber: '', leaveBehind: false }]);
  });

  it('asks before shipping without cartons that are not loaded', async () => {
    const user = userEvent.setup();
    doorState = one;
    replies[`POST ${BASE}/manifests/m1/close`] = [
      { status: 409, body: { detail: 'Not loaded yet: BOX2.', errors: { cartons: 'BOX2' } } },
      { body: { ...one.manifest, status: 'CLOSED' } },
    ];
    renderAt('/rf/loading');
    await user.click(await screen.findByRole('button', { name: /DC1-DOCK-01/ }));
    await user.click(await screen.findByRole('button', { name: 'Close manifest' }));
    await user.click(screen.getByRole('button', { name: 'Close manifest and ship' }));
    const ask = await screen.findByRole('group', { name: 'Not everything is loaded' });
    expect(ask).toHaveTextContent('Not loaded: BOX2');
    await user.click(within(ask).getByRole('button', { name: 'Ship without them' }));
    expect(await screen.findByText(/MF000001 is closed: 1 carton shipped/)).toBeInTheDocument();
    expect(sent('POST', `${BASE}/manifests/m1/close`).map((b) => b!.leaveBehind)).toEqual([false, true]);
  });

  it('takes a carton off, staying in that mode if the scan was wrong', async () => {
    const user = userEvent.setup();
    doorState = one;
    replies[`POST ${BASE}/manifests/m1/unload`] = [{ status: 400, body: { detail: 'BOX2 is not loaded here' } }, { body: waiting }];
    renderAt('/rf/loading');
    await user.click(await screen.findByRole('button', { name: /DC1-DOCK-01/ }));
    await user.click(await screen.findByRole('button', { name: 'Take a carton off' }));
    await scan(user, 'Scan a carton to take off', 'BOX2');
    expect(await screen.findByRole('alert')).toHaveTextContent('not loaded here');
    await scan(user, 'Scan a carton to take off', 'BOX1');
    expect(await screen.findByText('BOX1 taken off.')).toBeInTheDocument();
    expect(screen.getByLabelText('Scan a carton to load')).toBeInTheDocument();
    replies[`POST ${BASE}/manifests/m1/discard`] = [{ body: empty }];
    await user.click(screen.getByRole('button', { name: 'Discard this manifest' }));
    expect(await screen.findByText('MF000001 discarded.')).toBeInTheDocument();
  });
});
