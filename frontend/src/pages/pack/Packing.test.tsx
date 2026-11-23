import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PutBack } from '../../api/control';
import type { Carton, PackDetail, PackTask } from '../../api/packing';
import type { OrderRef } from '../../api/orders';
import { PackTaskDetailPage } from '../packing/PackTaskDetailPage';
import { PackTasksPage } from '../packing/PackTasksPage';
import { PackStationApp } from './PackStationApp';

const USER = { id: 'u1', userCode: 'ANN', displayName: 'Ann Lee' };
const SO1: OrderRef = { id: 'o1', orderNumber: 'SO001', customerName: 'Acme', status: 'PICKED' };
const SO2: OrderRef = { id: 'o2', orderNumber: 'SO002', customerName: 'Bluebird', status: 'PICKED' };
const STATIONS = [
  { id: 's1', locationId: 'DC1-PACK-01', waitingTasks: 1 },
  { id: 's2', locationId: 'DC1-PACK-02', waitingTasks: 0 },
];
const TYPES = [{ id: 'ct1', containerTypeId: 'CTN-M', description: 'Medium carton' }];

function detail(n: number, extra: Partial<PackDetail> = {}): PackDetail {
  const d = {
    id: `d${n}`,
    detailNumber: n,
    order: SO1,
    lineNumber: n,
    item: { id: 'i1', itemId: 'SKU-BAR', description: 'Protein bar' },
    lotNumber: null,
    serialNumber: null,
    quantity: 12,
    packedQuantity: 0,
    shortQuantity: 0,
    cancelledQuantity: 0,
    itemScans: [
      { value: '111000', uom: 'EA', eachesPerUnit: 1 },
      { value: 'SKU-BAR', uom: 'EA', eachesPerUnit: 1 },
      { value: '111006', uom: 'CS', eachesPerUnit: 6 },
    ],
    ...extra,
  };
  return { ...d, remaining: d.quantity - d.packedQuantity - d.shortQuantity - d.cancelledQuantity };
}

const DRILL: Partial<PackDetail> = {
  item: { id: 'i2', itemId: 'SKU-DRILL', description: 'Drill' },
  serialNumber: 'D-1',
  quantity: 1,
  itemScans: [{ value: '222000', uom: 'EA', eachesPerUnit: 1 }],
};

function carton(lpnId: string, extra: Partial<Carton> = {}): Carton {
  return {
    id: `c-${lpnId}`,
    container: { id: `k-${lpnId}`, lpnId },
    containerType: 'CTN-M',
    order: SO1,
    status: 'OPEN',
    weight: null,
    weightUom: null,
    totalQuantity: 0,
    contents: [],
    closedAt: null,
    closedBy: null,
    trackingNumber: null,
    loadedAt: null,
    ...extra,
  };
}

const IN_CARTON = [{ detailId: 'd1', lineNumber: 1, item: { id: 'i1', itemId: 'SKU-BAR', description: 'Protein bar' }, lotNumber: null, serialNumber: null, quantity: 12 }];

function task(details: PackDetail[], cartons: Carton[] = [], extra: Partial<PackTask> = {}): PackTask {
  return {
    id: 't1',
    taskNumber: 'PK000001',
    status: 'IN_PROGRESS',
    station: { id: 's1', locationId: 'DC1-PACK-01' },
    container: { id: 'k1', lpnId: 'TOTE1' },
    containerType: 'Standard tote',
    pickTask: { id: 'p1', taskNumber: 'PT000009' },
    orders: [...new Map(details.map((d) => [d.order.id, d.order])).values()],
    assignedTo: 'Ann Lee',
    assignedToId: 'u1',
    totalQuantity: details.reduce((s, d) => s + d.quantity, 0),
    packedQuantity: details.reduce((s, d) => s + d.packedQuantity, 0),
    shortQuantity: details.reduce((s, d) => s + d.shortQuantity, 0),
    canPackAsIs: false,
    details,
    cartons,
    createdAt: '2026-11-21T15:00:00Z',
    startedAt: '2026-11-21T15:05:00Z',
    completedAt: null,
    ...extra,
  };
}

type Reply = { status?: number; body: unknown };
let calls: { method: string; path: string; body?: Record<string, unknown> }[];
let replies: Record<string, Reply[]>;
let queue: PackTask[];
let putBack: PutBack[];
/** What reloading the task returns. */
let current: PackTask;

beforeEach(() => {
  putBack = [];
  localStorage.clear();
  calls = [];
  replies = {};
  queue = [task([detail(1)], [], { status: 'OPEN', assignedTo: null, assignedToId: null })];
  current = task([detail(1)]);
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET';
      calls.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : undefined });
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      const reply = replies[path]?.shift();
      if (reply) return json(reply.body, reply.status);
      if (method === 'GET') {
        if (path === '/api/rf/session') return json(USER);
        if (path.endsWith('/stations')) return json(STATIONS);
        if (path.startsWith('/api/rf/packing/stations/')) return json({ station: { id: 's1', locationId: 'DC1-PACK-01' }, tasks: queue });
        if (path === '/api/inventory/put-back') return json(putBack);
        if (path === '/api/rf/packing/carton-types') return json(TYPES);
        if (path === '/api/rf/packing/tasks/t1' || path === '/api/pack-tasks/t1') return json(current);
        if (path.startsWith('/api/pack-tasks?')) {
          return json({ content: [current], page: 0, size: 25, totalElements: 1, totalPages: 1 });
        }
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
        <Route path="/pack" element={<PackStationApp />} />
        <Route path="/pack-ship/packing-tasks" element={<PackTasksPage />} />
        <Route path="/pack-ship/packing-tasks/:id" element={<PackTaskDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function scan(user: UserEvent, label: string | RegExp, value: string) {
  await user.type(await screen.findByLabelText(label), `${value}{Enter}`);
}

const START = '/api/rf/packing/start';
const T1 = '/api/rf/packing/tasks/t1';
const sent = (path: string) => calls.filter((c) => c.method === 'POST' && c.path === path).map((c) => c.body);

/** Signs on at station 1 and starts the given task. */
async function open(user: UserEvent, started: PackTask) {
  localStorage.setItem('wms.packStation', 'DC1-PACK-01');
  replies[START] = [{ body: started }];
  renderAt('/pack');
  await scan(user, 'Scan a container to pack', 'tote1');
  await screen.findByRole('heading', { name: 'PK000001' });
}

describe('Pack station', () => {
  it('asks which station it is, remembers it, and shows what is waiting', async () => {
    const user = userEvent.setup();
    renderAt('/pack');
    await scan(user, 'Pack station', 'DC1-BK-01');
    expect(await screen.findByRole('alert')).toHaveTextContent('DC1-BK-01 is not a pack station');
    await scan(user, 'Pack station', 'dc1-pack-01');
    const waiting = await screen.findByRole('list', { name: 'Waiting containers' });
    expect(within(waiting).getByText('TOTE1')).toBeInTheDocument();
    expect(within(waiting).getByText('12 EA to pack')).toBeInTheDocument();
    expect(localStorage.getItem('wms.packStation')).toBe('DC1-PACK-01');
    await user.click(screen.getByRole('button', { name: 'Change station' }));
    expect(await screen.findByText('Which pack station is this?')).toBeInTheDocument();
  });

  it('asks before taking over from another packer', async () => {
    localStorage.setItem('wms.packStation', 'DC1-PACK-01');
    replies[START] = [
      { status: 409, body: { detail: 'PK000001 is being packed by Bob Ray.', errors: { assignedTo: 'Bob Ray' } } },
      { body: task([detail(1)]) },
    ];
    const user = userEvent.setup();
    renderAt('/pack');
    await scan(user, 'Scan a container to pack', 'TOTE1');
    const group = await screen.findByRole('group', { name: 'Take over' });
    expect(group).toHaveTextContent('being packed by Bob Ray');
    await user.click(within(group).getByRole('button', { name: 'Take over TOTE1' }));
    expect(await screen.findByRole('heading', { name: 'PK000001' })).toBeInTheDocument();
    expect(sent(START).map((b) => b!.takeOver)).toEqual([false, true]);
  });

  it('packs a container into a carton and finishes', async () => {
    const user = userEvent.setup();
    const half = task([detail(1, { packedQuantity: 6 }), detail(2, DRILL)], [carton('BOX1', { totalQuantity: 6, contents: IN_CARTON })]);
    const most = task([detail(1, { packedQuantity: 12 }), detail(2, DRILL)], [carton('BOX1', { totalQuantity: 12, contents: IN_CARTON })]);
    const all = task(
      [detail(1, { packedQuantity: 12 }), detail(2, { ...DRILL, packedQuantity: 1 })],
      [carton('BOX1', { totalQuantity: 13, contents: IN_CARTON })],
    );
    replies[`${T1}/cartons`] = [{ body: task([detail(1), detail(2, DRILL)], [carton('BOX1')]) }];
    replies[`${T1}/pack`] = [{ body: half }, { body: most }, { body: all }];
    replies[`${T1}/cartons/c-BOX1/close`] = [{ body: { ...all, status: 'COMPLETED' } }];
    await open(user, task([detail(1), detail(2, DRILL)]));

    await user.type(screen.getByLabelText('Carton label'), 'box1');
    await user.click(await screen.findByRole('button', { name: /CTN-M/ }));
    // Two cases possible, so the quantity is asked for.
    await scan(user, 'Scan item', '111006');
    await scan(user, 'How many CS? Up to 2', '3');
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many: only 2 CS left to pack');
    await scan(user, 'How many CS? Up to 2', '1');
    expect(await screen.findByText('Packed 1 CS of SKU-BAR')).toBeInTheDocument();
    // Only one case left: it goes straight in.
    await scan(user, 'Scan item', '111006');
    await scan(user, 'Scan item', '222000');
    await scan(user, 'Scan serial number', 'd-9');
    expect(await screen.findByRole('alert')).toHaveTextContent('D-9 is not to be packed here');
    await scan(user, 'Scan serial number', 'd-1');

    const close = await screen.findByRole('form', { name: 'Close BOX1' });
    expect(screen.queryByLabelText('Scan item')).not.toBeInTheDocument();
    await user.type(within(close).getByLabelText('Weight'), '0');
    await user.click(within(close).getByRole('button', { name: 'Close carton' }));
    expect(within(close).getByRole('alert')).toBeInTheDocument();
    await user.clear(within(close).getByLabelText('Weight'));
    await user.type(within(close).getByLabelText('Weight'), '4.25');
    await user.click(within(close).getByRole('button', { name: 'Close carton' }));
    expect(await screen.findByText('PK000001 is packed in 1 carton. TOTE1 is empty.')).toBeInTheDocument();

    expect(sent(`${T1}/cartons`)).toEqual([{ orderId: 'o1', containerTypeId: 'ct1', lpnId: 'box1' }]);
    expect(sent(`${T1}/pack`)).toEqual([
      { cartonId: 'c-BOX1', packedSoFar: 0, itemBarcode: '111006', serialNumber: null, lotNumber: null, quantity: 1 },
      { cartonId: 'c-BOX1', packedSoFar: 6, itemBarcode: '111006', serialNumber: null, lotNumber: null, quantity: 1 },
      { cartonId: 'c-BOX1', packedSoFar: 12, itemBarcode: '222000', serialNumber: 'D-1', lotNumber: null, quantity: 1 },
    ]);
    expect(sent(`${T1}/cartons/c-BOX1/close`)).toEqual([{ weight: 4.25, weightUom: 'LB' }]);
  });

  it('asks which lot when there are several', async () => {
    const user = userEvent.setup();
    const details = [detail(1, { lotNumber: 'OLD', quantity: 4 }), detail(2, { lotNumber: 'NEW', quantity: 2 })];
    replies[`${T1}/pack`] = [{ body: task([{ ...details[0], packedQuantity: 3, remaining: 1 }, details[1]], [carton('BOX1')]) }];
    await open(user, task(details, [carton('BOX1')]));
    await scan(user, 'Scan item', '111000');
    const lots = screen.getByRole('group', { name: 'Which lot?' });
    await user.click(within(lots).getByRole('button', { name: /OLD · 4 EA left/ }));
    await scan(user, 'How many EA? Up to 4', '3');
    await waitFor(() => expect(sent(`${T1}/pack`)).toEqual([expect.objectContaining({ lotNumber: 'OLD', quantity: 3 })]));
  });

  it('stops scans that do not belong', async () => {
    const user = userEvent.setup();
    const other = detail(2, { ...DRILL, order: SO2 });
    await open(user, task([detail(1, { quantity: 3 }), other], [carton('BOX1')]));
    await scan(user, 'Scan item', '999');
    expect(await screen.findByRole('alert')).toHaveTextContent('That item is not in TOTE1');
    await scan(user, 'Scan item', '222000');
    expect(await screen.findByRole('alert')).toHaveTextContent('No more SKU-DRILL for SO001. SO002 needs it');
    await scan(user, 'Scan item', '111006');
    expect(await screen.findByRole('alert')).toHaveTextContent('One CS is 6 EA, but only 3 EA are left');
    expect(sent(`${T1}/pack`)).toEqual([]);
  });

  it('moves to the next order in a batch container', async () => {
    const user = userEvent.setup();
    const second = detail(2, { order: SO2, quantity: 2 });
    const done = detail(1, { packedQuantity: 12 });
    replies[`${T1}/cartons/c-BOX1/close`] = [{ body: task([done, second], [carton('BOX1', { status: 'CLOSED', contents: IN_CARTON, totalQuantity: 12 })]) }];
    await open(user, task([done, second], [carton('BOX1', { contents: IN_CARTON, totalQuantity: 12 })]));
    expect(screen.getByRole('button', { name: /SO001/ })).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByRole('button', { name: 'Close carton' }));
    expect(await screen.findByText('Open a carton for SO002')).toBeInTheDocument();
    expect(sent(`${T1}/cartons/c-BOX1/close`)).toEqual([{ weight: null, weightUom: null }]);
  });

  it('ships a picking carton as it is', async () => {
    const user = userEvent.setup();
    const start = task([detail(1)], [], { canPackAsIs: true, container: { id: 'k1', lpnId: 'OLPN1' } });
    replies[`${T1}/pack-as-is`] = [
      { body: { ...start, status: 'COMPLETED', cartons: [carton('OLPN1', { container: { id: 'k1', lpnId: 'OLPN1' } })] } },
    ];
    await open(user, start);
    await user.click(screen.getByRole('button', { name: 'Ship as is' }));
    expect(await screen.findByText('PK000001 is packed in 1 carton.')).toBeInTheDocument();
  });

  it('reports missing items and empties a carton after asking', async () => {
    const user = userEvent.setup();
    const started = task([detail(1, { packedQuantity: 2 })], [carton('BOX1', { totalQuantity: 2, contents: IN_CARTON })]);
    const short = task([detail(1, { packedQuantity: 2, shortQuantity: 10 })], started.cartons);
    replies[`${T1}/missing`] = [{ body: short }];
    replies[`${T1}/cartons/c-BOX1/empty`] = [{ body: task([detail(1, { shortQuantity: 10 })]) }];
    await open(user, started);
    await user.click(screen.getByRole('button', { name: 'Report SKU-BAR missing' }));
    await user.click(screen.getByRole('button', { name: 'Report missing' }));
    expect(await screen.findByText('SKU-BAR reported missing.')).toBeInTheDocument();
    expect(sent(`${T1}/missing`)).toEqual([{ detailId: 'd1', remaining: 10 }]);
    await user.click(screen.getByRole('button', { name: 'Empty this carton' }));
    await user.click(screen.getByRole('button', { name: 'Empty carton' }));
    expect(await screen.findByText('BOX1 emptied and discarded.')).toBeInTheDocument();
  });

  it('leaves what is for a cancelled order in the container', async () => {
    const user = userEvent.setup();
    const left = [detail(1, { cancelledQuantity: 12 }), detail(2, { order: SO2, quantity: 2, packedQuantity: 2 })];
    const cartons = [carton('BOX1', { status: 'CANCELLED', contents: IN_CARTON, totalQuantity: 12 }), carton('BOX2', { order: SO2, contents: IN_CARTON, totalQuantity: 2 })];
    const open2 = task(left, cartons, { orders: [SO2] });
    replies[`${T1}/cartons/c-BOX2/close`] = [{ body: { ...open2, status: 'COMPLETED' } }];
    await open(user, open2);
    expect(screen.getByRole('note')).toHaveTextContent('Leave it there: it goes back to storage.');
    expect(within(screen.getByRole('table', { name: 'Container contents' })).getAllByRole('row')).toHaveLength(2);
    expect(screen.getByRole('region', { name: 'Carton BOX1' })).toHaveTextContent('Called off');
    await user.click(screen.getByRole('button', { name: 'Close carton' }));
    expect(await screen.findByText('PK000001 is packed in 1 carton. What is left in TOTE1 goes back to storage.')).toBeInTheDocument();
  });

  it('says so when all of it is called off', async () => {
    const user = userEvent.setup();
    replies[`${T1}/pack`] = [{ status: 409, body: { detail: 'PK000001 is called off: what is in TOTE1 goes back to storage.' } }];
    current = task([detail(1, { cancelledQuantity: 12 })], [], { status: 'CANCELLED' });
    await open(user, task([detail(1)], [carton('BOX1')]));
    await scan(user, 'Scan item', '111006');
    await scan(user, /Quantity|How many/, '1');
    expect(await screen.findByText('PK000001 is called off: what is in TOTE1 goes back to storage.')).toBeInTheDocument();
    expect(screen.getByLabelText('Scan a container to pack')).toBeInTheDocument();
  });

  it('notices when someone else has taken over', async () => {
    const user = userEvent.setup();
    replies[`${T1}/pack`] = [{ status: 409, body: { detail: 'PK000001 is being packed by Bob Ray.' } }];
    current = task([detail(1)], [], { assignedTo: 'Bob Ray', assignedToId: 'u2' });
    await open(user, task([detail(1, { quantity: 1 })], [carton('BOX1')]));
    await scan(user, 'Scan item', '111000');
    expect(await screen.findByText('PK000001 is now being packed by Bob Ray.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Back to the queue' }));
    expect(await screen.findByLabelText('Scan a container to pack')).toBeInTheDocument();
  });
});

describe('Packing tasks', () => {
  it('lists tasks and filters them', async () => {
    const user = userEvent.setup();
    renderAt('/pack-ship/packing-tasks');
    const table = await screen.findByRole('table', { name: 'Packing tasks' });
    expect(within(table).getByRole('link', { name: 'PK000001' })).toHaveAttribute('href', '/pack-ship/packing-tasks/t1');
    expect(within(table).getByText('0 / 12 EA')).toBeInTheDocument();
    expect(within(table).getByText('Packing')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Pack station' })).toHaveAttribute('href', '/pack');
    await user.selectOptions(screen.getByLabelText('Status'), 'COMPLETED');
    await screen.findByRole('option', { name: 'DC1-PACK-02' });
    await user.selectOptions(screen.getByLabelText('Station'), 's2');
    await waitFor(() => expect(calls.some((c) => c.path.includes('status=COMPLETED') && c.path.includes('stationId=s2'))).toBe(true));
  });

  it('shows what is to be put back from the stations', async () => {
    putBack = [
      { location: 'DC1-PACK-01', lpn: 'TOTE1', items: ['SKU-BAR', 'SKU-CUP'], quantity: 12, held: 0 },
      { location: 'DC1-PACK-02', lpn: null, items: ['SKU-BAR'], quantity: 3, held: 3 },
    ];
    renderAt('/pack-ship/packing-tasks');
    const table = await screen.findByRole('table', { name: 'To put back' });
    expect(within(table).getByText('TOTE1').closest('tr')).toHaveTextContent('DC1-PACK-01TOTE1SKU-BAR, SKU-CUP12 EA');
    expect(within(table).getByText('Loose').closest('tr')).toHaveTextContent('3 EA3 on hold or damaged');
  });

  it('shows a task with its details and cartons', async () => {
    current = task(
      [detail(1, { packedQuantity: 10, shortQuantity: 2 })],
      [carton('BOX1', { status: 'CLOSED', totalQuantity: 10, weight: 4.25, weightUom: 'LB', contents: IN_CARTON, closedBy: 'Ann Lee' })],
    );
    renderAt('/pack-ship/packing-tasks/t1');
    expect(await screen.findByRole('heading', { name: /PK000001/ })).toBeInTheDocument();
    expect(screen.getByText('10 of 12 EA · 2 missing')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'PT000009' })).toHaveAttribute('href', '/picking/tasks/p1');
    const box = screen.getByRole('region', { name: 'Carton BOX1' });
    expect(box).toHaveTextContent('CTN-M · 10 EA · 4.25');
    expect(box).toHaveTextContent('Packed by Ann Lee');
    expect(within(box).getByText('SKU-BAR')).toBeInTheDocument();
  });
});
