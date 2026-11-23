import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../../App';
import { InventoryByItemPage } from '../inventory/control/InventoryByItemPage';
import { SessionContext } from '../../session/SessionContext';
import { FacilitiesPage } from './FacilitiesPage';
import { ItemsPage } from './ItemsPage';
import { OwnersPage } from './OwnersPage';
import { NewOrderForm } from '../outbound/NewOrderForm';
import { RfApp } from '../rf/RfApp';

const MAIN = { id: 'f1', code: 'MAIN', name: 'Main warehouse' };
const EAST = { id: 'f2', code: 'EAST', name: 'East side' };
const HOUSE = { id: 'o1', code: 'HOUSE', name: 'Our own stock', status: 'ACTIVE', version: 0 };
const ACME = { id: 'o2', code: 'ACME', name: 'Acme Foods', status: 'ACTIVE', version: 2 };
const USER = { id: 'u1', userCode: 'BOSS', displayName: 'The Boss', permissions: ['DESKTOP', 'MASTER_DATA', 'FACILITIES'], mustChangePassword: false, facility: MAIN, facilities: [MAIN, EAST] };
const SUM = { lotNumber: null, expiryDate: null, total: 12, available: 12, reserved: 0, onHold: 0, damaged: 0, expired: 0, elsewhere: 0 };
const CUP = {
  id: 'i1', owner: ACME, itemId: 'CUP', description: 'Cup', status: 'ACTIVE', barcode: '111', lotControlled: false, serialControlled: false, expiryControlled: false,
  shelfLifeDays: null, width: null, depth: null, height: null, dimensionUom: null, weight: null, weightUom: null, uoms: [], createdAt: '2026-11-22T10:00:00Z',
  updatedAt: '2026-11-22T10:00:00Z', version: 0,
};

const COUNTING = { counting: { id: 'c1', number: 'CC1', location: 'BK-1', entries: [] }, waitingCount: 0, waiting: [], message: null };

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let replies: Record<string, { status?: number; body: unknown }[]>;
let urls: string[];
let owners: unknown[];
let here: typeof MAIN;

beforeEach(() => {
  sent = [];
  replies = {};
  urls = [];
  owners = [ACME, HOUSE];
  here = MAIN;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      const page = (...content: unknown[]) => json({ content, page: 0, size: 25, totalElements: content.length, totalPages: 1 });
      const method = init.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
        const reply = replies[path]?.shift();
        if (reply) return json(reply.body, reply.status);
        if (path.endsWith('/session/facility')) {
          here = EAST;
          return json({ ...USER, facility: EAST });
        }
        return json({ ...ACME, id: 'new', code: String(sent[sent.length - 1].body?.code ?? 'X') });
      }
      urls.push(path);
      if (path === '/api/session') return json({ state: 'SIGNED_IN', user: { ...USER, facility: here }, setupHere: false });
      if (path === '/api/rf/session') return json({ ...USER, permissions: ['DEVICE_COUNT'], facility: here });
      if (path === '/api/navigation/modules') return json([]);
      if (path === '/api/facilities') return json([{ ...EAST, address: '1 Road', status: 'ACTIVE', version: 4 }, { ...MAIN, address: null, status: 'INACTIVE', version: 0 }]);
      if (path === '/api/owners') return json(owners);
      if (path.startsWith('/api/items')) return page(CUP);
      if (path === '/api/uoms') return json([]);
      if (path === '/api/rf/counts') return json(COUNTING);
      if (path === '/api/rf/counts/items/111') return json({ detail: '111 could be an item of ACME, HOUSE. Say whose it is.', errors: { owner: 'ACME,HOUSE' } }, 409);
      if (path === '/api/rf/counts/items/111?owner=ACME') return json({ item: { ...CUP, owner: 'ACME' }, scan: { value: '111', uom: 'EA', eachesPerUnit: 1 } });
      if (path.startsWith('/api/inventory/on-hand')) {
        const all = path.includes('everywhere=true');
        return page({ item: { ...CUP, owner: 'ACME' }, total: SUM, lots: [], facilities: all ? [{ facility: 'EAST', total: { ...SUM, total: 5 } }, { facility: 'MAIN', total: { ...SUM, total: 7 } }] : null });
      }
      return page();
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function open(path: string, element?: React.ReactElement) {
  render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      {element ? (
        <Routes>
          <Route path={path.startsWith('/rf') ? '/rf/*' : '/*'} element={element} />
        </Routes>
      ) : (
        <App />
      )}
    </MemoryRouter>,
  );
}

describe('Facilities', () => {
  it('are listed and changed', async () => {
    const user = userEvent.setup();
    open('/master-data/facilities', <FacilitiesPage />);
    const rows = within(await screen.findByRole('table', { name: 'Facilities' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('EASTEast side1 RoadActive');
    expect(rows[2]).toHaveTextContent('MAINMain warehouse—Inactive');
    await user.click(screen.getByRole('button', { name: 'EAST' }));
    await user.clear(screen.getByLabelText('Address'));
    await user.type(screen.getByLabelText('Address'), '2 Road');
    replies['/api/facilities/f2'] = [{ status: 409, body: { detail: 'You are working in EAST.', errors: { status: 'you are working in it' } } }];
    await user.selectOptions(screen.getByLabelText('Status'), 'Inactive');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('You are working in it')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Saved EAST');
    expect(sent[1]).toEqual({ method: 'PUT', path: '/api/facilities/f2', body: { code: 'EAST', name: 'East side', status: 'INACTIVE', version: 4, address: '2 Road' } });
  });

  it('are created', async () => {
    const user = userEvent.setup();
    open('/master-data/facilities', <FacilitiesPage />);
    await user.click(await screen.findByRole('button', { name: 'New facility' }));
    await user.click(screen.getByRole('button', { name: 'Create facility' }));
    expect(screen.getByText('Enter a code')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Code'), 'west');
    await user.type(screen.getByLabelText('Name'), 'West');
    await user.click(screen.getByRole('button', { name: 'Create facility' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Created WEST');
    expect(sent[0].body).toEqual({ code: 'WEST', name: 'West', status: 'ACTIVE', address: null });
  });

  it('are gone to at the top of the screen, which starts over there', async () => {
    const user = userEvent.setup();
    open('/master-data/items');
    const choice = await screen.findByRole('combobox', { name: 'Facility' });
    expect(choice).toHaveValue('f1');
    expect(await screen.findByRole('heading', { name: 'Items' })).toBeInTheDocument();
    await user.selectOptions(choice, 'f2');
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Facility' })).toHaveValue('f2');
    expect(sent[0]).toEqual({ method: 'PUT', path: '/api/session/facility', body: { facilityId: 'f2' } });
  });

  it('follow what another tab did when this one is looked at again', async () => {
    open('/');
    expect(await screen.findByRole('combobox', { name: 'Facility' })).toHaveValue('f1');
    here = EAST;
    document.dispatchEvent(new Event('visibilitychange'));
    await vi.waitFor(() => expect(screen.getByRole('combobox', { name: 'Facility' })).toHaveValue('f2'));
  });

  it('are chosen on a device, once nothing is left half done', async () => {
    const user = userEvent.setup();
    open('/rf', <RfApp />);
    const choice = await screen.findByLabelText('Facility');
    replies['/api/rf/session/facility'] = [{ status: 409, body: { detail: 'Finish this first: Counting BK-1.' } }];
    await user.selectOptions(choice, 'f2');
    expect(await screen.findByRole('alert')).toHaveTextContent('Finish this first: Counting BK-1.');
    expect(screen.getByLabelText('Facility')).toHaveValue('f1');
    await user.selectOptions(screen.getByLabelText('Facility'), 'f2');
    await vi.waitFor(() => expect(screen.getByLabelText('Facility')).toHaveValue('f2'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('are looked at all together in inventory by item', async () => {
    const user = userEvent.setup();
    open(
      '/inventory/by-item',
      <SessionContext.Provider value={{ user: USER as never, signOut: () => undefined, changePassword: () => undefined }}>
        <InventoryByItemPage />
      </SessionContext.Provider>,
    );
    await user.click(await screen.findByRole('checkbox', { name: 'All facilities' }));
    const row = (await screen.findByText('EAST')).closest('tr')!;
    expect(row).toHaveTextContent('In EAST5');
    expect(screen.getByText('MAIN').closest('tr')).toHaveTextContent('In MAIN7');
    expect(urls.some((u) => u.includes('everywhere=true'))).toBe(true);
  });
});

describe('Owners', () => {
  it('are kept', async () => {
    const user = userEvent.setup();
    open('/master-data/owners', <OwnersPage />);
    await user.click(await screen.findByRole('button', { name: 'ACME' }));
    await user.clear(screen.getByLabelText('Name'));
    await user.type(screen.getByLabelText('Name'), 'Acme Ltd');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Saved ACME');
    expect(sent[0]).toEqual({ method: 'PUT', path: '/api/owners/o2', body: { code: 'ACME', name: 'Acme Ltd', status: 'ACTIVE', version: 2 } });
  });

  it('are told apart on items', async () => {
    const user = userEvent.setup();
    open('/master-data/items', <ItemsPage />);
    await user.selectOptions(await screen.findByLabelText('Owner'), 'ACME');
    await vi.waitFor(() => expect(urls.some((u) => u.startsWith('/api/items') && u.includes('owner=ACME'))).toBe(true));
    expect(screen.getByRole('button', { name: 'CUP' }).closest('th')).toHaveTextContent('CUPACME');
    await user.click(screen.getByRole('button', { name: 'New item' }));
    const dialog = within(screen.getByRole('dialog'));
    await user.type(dialog.getByLabelText('Item ID'), 'jar');
    await user.type(dialog.getByLabelText('Description'), 'Jar');
    await user.click(dialog.getByRole('button', { name: 'Create item' }));
    expect(dialog.getByText('Choose whose it is')).toBeInTheDocument();
    await user.selectOptions(dialog.getByLabelText('Owner'), 'ACME');
    await user.click(dialog.getByRole('button', { name: 'Create item' }));
    await vi.waitFor(() => expect(sent[0]?.body).toMatchObject({ itemId: 'JAR', owner: 'ACME' }));
  });

  it('are not spoken of while there is only one', async () => {
    owners = [HOUSE];
    open('/master-data/items', <ItemsPage />);
    expect(await screen.findByRole('button', { name: 'CUP' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Owner')).not.toBeInTheDocument();
    expect(screen.queryByText('ACME')).not.toBeInTheDocument();
  });

  it('decide which items an order can have', async () => {
    const user = userEvent.setup();
    open('/', <NewOrderForm onClose={() => undefined} onCreated={() => undefined} />);
    await user.selectOptions(await screen.findByLabelText('Owner'), 'ACME');
    await user.type(screen.getByLabelText('Customer'), 'Shop');
    await user.type(screen.getByLabelText('Item'), 'cup');
    await user.click(await screen.findByRole('option', { name: /CUP/ }));
    expect(urls.some((u) => u.includes('owner=ACME'))).toBe(true);
    await user.type(screen.getByLabelText('Quantity (EA)'), '3');
    // Another owner has other items.
    await user.selectOptions(screen.getByLabelText('Owner'), 'HOUSE');
    await user.click(screen.getByRole('button', { name: 'Create order' }));
    expect(await screen.findByText('Choose an item')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Owner'), 'ACME');
    await user.type(screen.getByLabelText('Item'), 'cup');
    await user.click(await screen.findByRole('option', { name: /CUP/ }));
    await user.click(screen.getByRole('button', { name: 'Create order' }));
    await vi.waitFor(() => expect(sent[0]?.body).toMatchObject({ owner: 'ACME', customerName: 'Shop' }));
  });

  it('are asked for when a counted item could be several', async () => {
    const user = userEvent.setup();
    replies['/api/rf/counts/c1/entries'] = [{ body: COUNTING }];
    open('/rf/count', <RfApp />);
    await user.type(await screen.findByLabelText('Item'), '111{Enter}');
    await user.click(await screen.findByRole('button', { name: 'ACME' }));
    await user.type(await screen.findByLabelText(/^How many/), '4{Enter}');
    await vi.waitFor(() => expect(sent[0]?.body).toMatchObject({ itemBarcode: '111', owner: 'ACME', quantity: 4 }));
    // Counted, so the device is ready for the next item.
    expect(await screen.findByLabelText('Item')).toBeInTheDocument();
  });
});
