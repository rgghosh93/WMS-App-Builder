import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PickDirective, PickingState, TaskProgress } from '../../api/rf';
import { RfApp } from './RfApp';

const USER = { id: 'u1', userCode: 'ANN', displayName: 'Ann Lee' };
const WAITING: PickingState = { step: 'SCAN_CONTAINER', waitingTasks: 2, task: null, pick: null, drop: null, newContainer: null };
const TASK: TaskProgress = {
  id: 't1',
  taskNumber: 'PT000009',
  pickMethod: 'DISCRETE',
  orderCount: 1,
  toLpnId: 'TOTE00001',
  toContainerType: 'Standard picking tote',
  closedDetails: 0,
  detailCount: 2,
  canRelease: true,
};
const BARS: PickDirective = {
  detailId: 'd1',
  detailNumber: 1,
  orderNumber: 'SO001002',
  customerName: 'Bluebird Grocers',
  locationId: 'DC1-BK-01-01-A-01',
  fromLpnId: null,
  item: { id: 'i1', itemId: 'SKU-20001', description: 'Protein bar' },
  lotNumber: 'PB2610A',
  expiryDate: '2027-04-15',
  serialNumber: null,
  quantity: 27,
  pickedQuantity: 0,
  remaining: 27,
  directed: [
    { uom: 'CS', quantity: 4, eachesPerUnit: 6 },
    { uom: 'EA', quantity: 3, eachesPerUnit: 1 },
  ],
  itemScans: [
    { value: '111000', uom: 'EA', eachesPerUnit: 1 },
    { value: 'SKU-20001', uom: 'EA', eachesPerUnit: 1 },
    { value: '111006', uom: 'CS', eachesPerUnit: 6 },
  ],
};
const DRILL: PickDirective = {
  ...BARS,
  detailId: 'd2',
  detailNumber: 2,
  locationId: 'DC1-RS-01-01-A-01',
  fromLpnId: 'C00000005',
  item: { id: 'i2', itemId: 'SKU-10001', description: 'Cordless drill' },
  lotNumber: null,
  expiryDate: null,
  serialNumber: 'DR18-000104',
  quantity: 1,
  remaining: 1,
  directed: [{ uom: 'EA', quantity: 1, eachesPerUnit: 1 }],
  itemScans: [{ value: '222000', uom: 'EA', eachesPerUnit: 1 }],
};
const picking = (pick: PickDirective, task: Partial<TaskProgress> = {}): PickingState => ({
  ...WAITING,
  step: 'PICK',
  task: { ...TASK, ...task },
  pick,
});
const DROP: PickingState = {
  ...WAITING,
  step: 'DROP',
  task: { ...TASK, closedDetails: 2, canRelease: false },
  drop: { suggestedLocationId: 'DC1-PACK-01' },
};

type Reply = { status?: number; body: unknown };
let calls: { method: string; path: string; body?: Record<string, unknown> }[];
let signedIn: boolean;
let state: PickingState;
/** Replies to the next POSTs, by path. */
let replies: Record<string, Reply[]>;

beforeEach(() => {
  calls = [];
  signedIn = true;
  state = WAITING;
  replies = {};
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET';
      calls.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : undefined });
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      if (path === '/api/rf/session' && method === 'DELETE') return new Response(null, { status: 204 });
      if (method === 'GET' && !signedIn) return json({ detail: 'Sign in to continue' }, 401);
      if (path === '/api/rf/session' && method === 'GET') return json(USER);
      if (path === '/api/rf/picking' && method === 'GET') return json(state);
      const reply = replies[path]?.shift();
      return reply ? json(reply.body, reply.status) : json({ detail: 'Not found' }, 404);
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
        <Route path="/rf/*" element={<RfApp />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function scan(user: UserEvent, label: string | RegExp, value: string) {
  await user.type(await screen.findByLabelText(label), `${value}{Enter}`);
}

const sent = (path: string) => calls.filter((c) => c.method === 'POST' && c.path === path).map((c) => c.body);

describe('Signing in', () => {
  it('asks for a user ID and PIN, then shows the transactions', async () => {
    signedIn = false;
    replies['/api/rf/session'] = [{ status: 401, body: { detail: 'User ID or PIN is not right' } }, { body: USER }];
    const user = userEvent.setup();
    renderAt('/rf');
    await user.type(await screen.findByLabelText('User ID'), 'ann');
    await user.type(screen.getByLabelText('PIN'), '0000{Enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('User ID or PIN is not right');
    expect(screen.getByLabelText('PIN')).toHaveValue('');

    await user.type(screen.getByLabelText('PIN'), '1357{Enter}');
    expect(await screen.findByRole('link', { name: 'Picking' })).toHaveAttribute('href', '/rf/picking');
    expect(screen.getByText('Ann Lee')).toBeInTheDocument();
  });

  it('goes back to sign-in when signing out or when the session has ended', async () => {
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await screen.findByLabelText('Container');
    replies['/api/rf/picking/start'] = [{ status: 401, body: { detail: 'Sign in to continue' } }];
    await scan(user, 'Container', 'TOTE00001');
    expect(await screen.findByLabelText('User ID')).toBeInTheDocument();
  });
});

describe('Picking', () => {
  it('walks a task from container to pack station', async () => {
    const user = userEvent.setup();
    replies['/api/rf/picking/start'] = [{ body: picking(BARS) }];
    replies['/api/rf/picking/confirm'] = [
      {
        body: picking(
          { ...BARS, pickedQuantity: 24, remaining: 3, directed: [{ uom: 'EA', quantity: 3, eachesPerUnit: 1 }] },
          { canRelease: false },
        ),
      },
      { body: picking(DRILL, { closedDetails: 1, canRelease: false }) },
      { body: DROP },
    ];
    replies['/api/rf/picking/drop'] = [{ body: WAITING }];
    renderAt('/rf/picking');

    expect(await screen.findByText('2 pick tasks waiting')).toBeInTheDocument();
    await scan(user, 'Container', 'tote00001');
    expect(await screen.findByRole('heading', { name: 'PT000009' })).toBeInTheDocument();
    expect(screen.getByText('Pick 1 of 2')).toBeInTheDocument();

    // Cases first.
    await scan(user, 'Scan location', 'dc1-bk-01-01-a-01');
    expect(screen.getByText('4 CS + 3 EA')).toBeInTheDocument();
    await scan(user, 'Scan item', '111006');
    await scan(user, /Put 4 CS in TOTE00001/, '4');
    expect(await screen.findByText('Picked 4 CS of SKU-20001')).toBeInTheDocument();
    // Same place, so only the loose item is scanned next.
    await scan(user, 'Scan item', '111000');
    await scan(user, /Put 3 EA in TOTE00001/, '3');

    // Stock in a carton, with a serial number.
    expect(await screen.findByText('Pick 2 of 2')).toBeInTheDocument();
    await scan(user, 'Scan location', 'DC1-RS-01-01-A-01');
    await scan(user, 'Scan container', 'C00000005');
    await scan(user, 'Scan item', '222000');
    await scan(user, 'Scan serial number', 'DR18-000104');
    await scan(user, /Put 1 EA/, '1');

    expect(await screen.findByText('Task picked. Drop TOTE00001 at pack station')).toBeInTheDocument();
    expect(screen.getByText('DC1-PACK-01')).toBeInTheDocument();
    await scan(user, 'Pack station', 'DC1-PACK-01');
    expect(await screen.findByText('TOTE00001 dropped at DC1-PACK-01. PT000009 is complete.')).toBeInTheDocument();
    expect(screen.getByLabelText('Container')).toBeInTheDocument();

    expect(sent('/api/rf/picking/start')).toEqual([{ lpnId: 'tote00001', containerTypeId: null }]);
    expect(sent('/api/rf/picking/confirm')).toEqual([
      expect.objectContaining({ detailId: 'd1', pickedSoFar: 0, itemBarcode: '111006', quantity: 4, shortPick: false }),
      expect.objectContaining({ detailId: 'd1', pickedSoFar: 24, itemBarcode: '111000', quantity: 3, shortPick: false }),
      expect.objectContaining({ detailId: 'd2', fromLpnId: 'C00000005', serialNumber: 'DR18-000104', quantity: 1 }),
    ]);
  });

  it('stops wrong scans before anything is sent', async () => {
    state = picking(DRILL);
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await scan(user, 'Scan location', 'DC1-BK-09-09-A-01');
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong location. Go to DC1-RS-01-01-A-01');
    expect(screen.getByLabelText('Scan location')).toHaveValue('');
    await scan(user, 'Scan location', 'DC1-RS-01-01-A-01');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await scan(user, 'Scan container', 'C00000009');
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong container. Pick from C00000005');
    await scan(user, 'Scan container', 'C00000005');
    await scan(user, 'Scan item', '111000');
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong item. Pick SKU-10001');
    await scan(user, 'Scan item', '222000');
    await scan(user, 'Scan serial number', 'DR18-000999');
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong serial number. Pick DR18-000104');
    await scan(user, 'Scan serial number', 'DR18-000104');
    await scan(user, /Confirm quantity/, '2');
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many: pick 1 EA');
    expect(sent('/api/rf/picking/confirm')).toEqual([]);
  });

  it('refuses a unit bigger than what is left', async () => {
    state = picking({ ...BARS, pickedQuantity: 24, remaining: 3, directed: [{ uom: 'EA', quantity: 3, eachesPerUnit: 1 }] });
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await scan(user, 'Scan location', BARS.locationId);
    await scan(user, 'Scan item', '111006');
    expect(await screen.findByRole('alert')).toHaveTextContent('One CS is 6 EA, but only 3 EA are left');
  });

  it('asks why when less than directed is entered', async () => {
    state = picking(BARS);
    replies['/api/rf/picking/confirm'] = [{ body: picking(DRILL, { closedDetails: 1 }) }];
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await scan(user, 'Scan location', BARS.locationId);
    await scan(user, 'Scan item', '111006');
    await scan(user, /Put 4 CS/, '2');
    const why = screen.getByRole('group', { name: 'Less than directed' });
    expect(why).toHaveTextContent('2 of 4 CS');
    expect(sent('/api/rf/picking/confirm')).toEqual([]);
    await user.click(within(why).getByRole('button', { name: /Short/ }));
    expect(await screen.findByText(/Picked 2 CS of SKU-20001. The rest is reported short/)).toBeInTheDocument();
    expect(sent('/api/rf/picking/confirm')).toEqual([expect.objectContaining({ quantity: 2, shortPick: true })]);
  });

  it('can report that nothing is there, without the container that is missing', async () => {
    state = picking(DRILL);
    replies['/api/rf/picking/confirm'] = [{ body: WAITING }];
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await scan(user, 'Scan location', DRILL.locationId);
    await user.click(screen.getByRole('button', { name: "Can't find it" }));
    await user.click(screen.getByRole('button', { name: 'Report short' }));
    expect(await screen.findByText('Nothing could be picked, so the task is closed.')).toBeInTheDocument();
    expect(sent('/api/rf/picking/confirm')).toEqual([
      expect.objectContaining({ fromLpnId: null, itemBarcode: null, quantity: 0, shortPick: true }),
    ]);
  });

  it('asks what a new carton is', async () => {
    replies['/api/rf/picking/start'] = [
      {
        body: {
          ...WAITING,
          step: 'CHOOSE_CONTAINER_TYPE',
          newContainer: { lpnId: 'OLPN-1', types: [{ id: 'ct1', containerTypeId: 'CTN-M', description: 'Medium carton' }] },
        },
      },
      { body: picking(BARS, { toLpnId: 'OLPN-1' }) },
    ];
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await scan(user, 'Container', 'OLPN-1');
    await user.click(await screen.findByRole('button', { name: /CTN-M/ }));
    expect(await screen.findByLabelText('Scan location')).toBeInTheDocument();
    expect(sent('/api/rf/picking/start')[1]).toEqual({ lpnId: 'OLPN-1', containerTypeId: 'ct1' });
  });

  it('shows why a container or pack station was refused', async () => {
    replies['/api/rf/picking/start'] = [{ status: 400, body: { detail: 'T9 is already in use' } }];
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await scan(user, 'Container', 'T9');
    expect(await screen.findByRole('alert')).toHaveTextContent('T9 is already in use');
  });

  it('reloads when the pick has changed underneath', async () => {
    state = picking({ ...BARS, directed: [{ uom: 'EA', quantity: 27, eachesPerUnit: 1 }] });
    replies['/api/rf/picking/confirm'] = [{ status: 409, body: { detail: 'This pick has changed.' } }];
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await scan(user, 'Scan location', BARS.locationId);
    await scan(user, 'Scan item', '111000');
    state = DROP;
    await scan(user, /Put 27 EA/, '27');
    expect(await screen.findByLabelText('Pack station')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('This pick has changed.');
  });

  it('gives a task back', async () => {
    state = picking(BARS);
    replies['/api/rf/picking/release'] = [{ body: WAITING }];
    const user = userEvent.setup();
    renderAt('/rf/picking');
    await user.click(await screen.findByRole('button', { name: 'Give this task back' }));
    await waitFor(() => expect(screen.getByLabelText('Container')).toBeInTheDocument());
  });
});
