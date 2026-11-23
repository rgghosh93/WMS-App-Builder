import { render, screen, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReplenishState, ReplenishmentRule, ReplenishmentTask } from '../../api/replenishment';
import { RfApp } from '../rf/RfApp';
import { ReplenishmentRulesPage } from './ReplenishmentRulesPage';
import { ReplenishmentTasksPage } from './ReplenishmentTasksPage';

const CUP = { id: 'i1', itemId: 'SKU-CUP', description: 'Cup' };
const TASK: ReplenishmentTask = {
  id: 't1', taskNumber: 'RP000001', status: 'OPEN', reason: 'BELOW_MINIMUM', item: CUP, scans: [], lotNumber: 'L1', expiryDate: null,
  fromLocation: { id: 'l1', locationId: 'RS-1' }, fromLpn: 'P1', toLocation: { id: 'l2', locationId: 'PICK-1' },
  quantity: 27, movedQuantity: 0, shortQuantity: 0, assignedTo: null, note: null, createdAt: '2026-11-22T10:00:00Z', startedAt: null, endedAt: null,
};
const MINE: ReplenishmentTask = {
  ...TASK, status: 'IN_PROGRESS', scans: [{ value: '333000', uom: 'EA', eachesPerUnit: 1 }, { value: '333006', uom: 'CS', eachesPerUnit: 6 }],
};
const RULE: ReplenishmentRule = {
  id: 'r1', item: CUP, location: TASK.toLocation, minQuantity: 10, maxQuantity: 50, multiple: 6, sourceZone: { id: 'z1', code: 'BULK', name: 'Bulk' },
  forOrders: true, status: 'ACTIVE', onHand: 5, reserved: 2, coming: 0, waiting: 0, needed: 42, version: 3,
};
const IDLE: ReplenishState = { task: null, waitingCount: 1, waiting: [TASK] };
const NONE: ReplenishState = { task: null, waitingCount: 0, waiting: [] };
const doing = (extra: Partial<ReplenishmentTask> = {}): ReplenishState => ({ task: { ...MINE, ...extra }, waitingCount: 0, waiting: [] });

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let replies: Record<string, { status?: number; body: unknown }[]>;
let state: ReplenishState;

beforeEach(() => {
  sent = [];
  replies = {};
  state = IDLE;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
      const page = (...content: unknown[]) => json({ content, page: 0, size: 25, totalElements: content.length, totalPages: 1 });
      const method = init.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
        const reply = replies[path]?.shift();
        return reply ? json(reply.body, reply.status) : method === 'DELETE' ? json(null, 204) : json(RULE);
      }
      if (path === '/api/rf/session') return json({ id: 'u1', userCode: 'ANN', displayName: 'Ann Lee' });
      if (path === '/api/rf/replenishment') return json(state);
      if (path === '/api/replenishment/rules') return json([RULE, { ...RULE, id: 'r2', status: 'INACTIVE', onHand: null, reserved: null, coming: null, waiting: null, needed: null, sourceZone: null }]);
      if (path.startsWith('/api/zones')) return json([{ ...RULE.sourceZone, function: 'REPLENISHMENT', status: 'ACTIVE', priority: null, allocatable: true, locationCount: 1, version: 0 }]);
      if (path.startsWith('/api/items')) return page({ ...CUP, status: 'ACTIVE', serialControlled: false, uoms: [{ id: 'u1', uom: { uomCode: 'CS' }, quantity: 6 }] });
      if (path.startsWith('/api/locations')) return page({ id: 'l2', locationId: 'PICK-1' });
      return page({ ...TASK }, { ...TASK, id: 't2', taskNumber: 'RP000002', status: 'COMPLETED', movedQuantity: 20, shortQuantity: 7, assignedTo: 'Ann Lee' });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function open(path: string) {
  render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/replenishment/rules" element={<ReplenishmentRulesPage />} />
        <Route path="/replenishment/tasks" element={<ReplenishmentTasksPage />} />
        <Route path="/rf/*" element={<RfApp />} />
      </Routes>
    </MemoryRouter>,
  );
}

const scan = async (user: UserEvent, label: string | RegExp, value: string) => user.type(await screen.findByLabelText(label), `${value}{Enter}`);
const CONFIRM = '/api/rf/replenishment/tasks/t1/confirm';

describe('Replenishment rules', () => {
  it('show how each pick location stands', async () => {
    open('/replenishment/rules');
    const rows = within(await screen.findByRole('table', { name: 'Replenishment rules' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('PICK-1SKU-CUPCup1050by 65200BULK42 EA needed');
    expect(rows[2]).toHaveTextContent('————AnywhereInactive');
  });

  it('are created', async () => {
    const user = userEvent.setup();
    open('/replenishment/rules');
    await user.click(await screen.findByRole('button', { name: 'New rule' }));
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(screen.getByText('Choose an item')).toBeInTheDocument();
    expect(screen.getByText('Choose a location')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Item'), 'cup');
    await user.click(await screen.findByRole('option', { name: /SKU-CUP/ }));
    await user.type(screen.getByLabelText('Pick location'), 'pick');
    await user.click(await screen.findByRole('option', { name: 'PICK-1' }));
    await user.type(screen.getByLabelText('Minimum (EA)'), '10');
    await user.type(screen.getByLabelText('Maximum (EA)'), '10');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(screen.getByText('Must be more than the minimum')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Maximum (EA)'), '0');
    await user.click(screen.getByRole('button', { name: 'CS (6 EA)' }));
    await user.selectOptions(await screen.findByLabelText('Replenishment zone'), 'z1');
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Created the rule for SKU-CUP at PICK-1');
    expect(sent[0]).toEqual({
      method: 'POST', path: '/api/replenishment/rules',
      body: { itemId: 'i1', locationId: 'l2', minQuantity: 10, maxQuantity: 100, multiple: 6, sourceZoneId: 'z1', forOrders: true, status: 'ACTIVE' },
    });
  });

  it('are changed, deleted and run', async () => {
    const user = userEvent.setup();
    open('/replenishment/rules');
    await user.click((await screen.findAllByRole('button', { name: 'SKU-CUP at PICK-1' }))[0]);
    await user.click(screen.getByRole('checkbox', { name: /Also fill for orders/ }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByRole('status');
    expect(sent[0]).toMatchObject({ method: 'PUT', path: '/api/replenishment/rules/r1', body: { forOrders: false, version: 3, sourceZoneId: 'z1' } });
    await user.click((await screen.findAllByRole('button', { name: 'SKU-CUP at PICK-1' }))[0]);
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(within(screen.getByRole('group', { name: 'Confirm delete' })).getByRole('button', { name: 'Delete' }));
    await vi.waitFor(() => expect(sent[1]).toMatchObject({ method: 'DELETE', path: '/api/replenishment/rules/r1' }));
    replies['/api/replenishment/run'] = [{ body: { rules: 2, quantity: 42, still: 1 } }];
    await user.click(await screen.findByRole('button', { name: 'Run now' }));
    expect(await screen.findByText('Planned 42 EA. 1 rule is below the minimum, with nothing more to bring or no room for it.')).toBeInTheDocument();
  });
});

describe('Replenishment tasks', () => {
  it('are listed and cancelled', async () => {
    const user = userEvent.setup();
    open('/replenishment/tasks');
    const rows = within(await screen.findByRole('table', { name: 'Replenishment tasks' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('RP000001Below minimumSKU-CUPlot L1RS-1P1PICK-127 EAWaiting');
    expect(rows[2]).toHaveTextContent('20 moved7 missingDoneAnn Lee');
    expect(within(rows[2]).queryByRole('button')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel RP000001' }));
    replies['/api/replenishment/tasks/t1/cancel'] = [{ body: { ...TASK, status: 'CANCELLED' } }];
    await user.click(screen.getByRole('button', { name: 'Cancel it' }));
    expect(await screen.findByText('Cancelled RP000001')).toBeInTheDocument();
  });

  it('say why one cannot be cancelled', async () => {
    const user = userEvent.setup();
    open('/replenishment/tasks');
    await user.click(await screen.findByRole('button', { name: 'Cancel RP000001' }));
    replies['/api/replenishment/tasks/t1/cancel'] = [{ status: 409, body: { detail: 'RP000001 is already done.' } }];
    await user.click(screen.getByRole('button', { name: 'Cancel it' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('RP000001 is already done.');
  });
});

describe('Replenishing on a device', () => {
  it('takes stock a scan at a time and puts it in the pick location', async () => {
    const user = userEvent.setup();
    open('/rf/replenish');
    expect(await screen.findByText('1 task waiting')).toBeInTheDocument();
    replies['/api/rf/replenishment/start'] = [{ body: doing() }];
    await user.click(screen.getByRole('button', { name: 'Start the most urgent' }));
    expect(sent[0].body).toEqual({ taskId: null });

    await scan(user, 'Scan location', 'pick-1');
    expect(await screen.findByText('Wrong location. Go to RS-1')).toBeInTheDocument();
    await scan(user, 'Scan location', 'rs-1');
    await scan(user, 'Scan LPN', 'p1');
    expect(screen.getByText('4 CS + 3 EA')).toBeInTheDocument();
    await scan(user, 'Scan item', '999');
    expect(await screen.findByText('Wrong item. Take SKU-CUP')).toBeInTheDocument();
    await scan(user, 'Scan item', '333006');
    await scan(user, 'Quantity (CS of 6 EA), up to 4', '5');
    expect(await screen.findByText('Only 27 EA more are wanted')).toBeInTheDocument();
    await scan(user, /^Quantity/, '4');
    expect(screen.getByText('24 EA taken so far')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Put these in PICK-1 and come back' }));
    await user.click(screen.getByRole('button', { name: 'Count what I took again' }));
    expect(screen.queryByText(/taken so far/)).not.toBeInTheDocument();
    await scan(user, 'Scan item', '333006');
    await scan(user, /^Quantity/, '4');
    await scan(user, 'Scan item', '333006');
    expect(await screen.findByText(/only 3 EA more are wanted/)).toBeInTheDocument();
    await scan(user, 'Scan item', '333000');
    await scan(user, /^Quantity/, '3');

    expect(screen.getByText(/Put 4 CS \+ 3 EA of SKU-CUP in/)).toBeInTheDocument();
    replies[CONFIRM] = [
      { status: 409, body: { detail: 'PICK-1 takes at most 30 EA, and it would hold 32.', errors: { capacity: 'x' } } },
      { body: NONE },
    ];
    await scan(user, 'Pick location', 'pick-1');
    await user.click(await screen.findByRole('button', { name: 'Put it there anyway' }));
    expect(await screen.findByText('Put 27 EA of SKU-CUP in PICK-1.')).toBeInTheDocument();
    expect(screen.getByText('No replenishment is waiting.')).toBeInTheDocument();
    expect(sent.filter((s) => s.path === CONFIRM).map((s) => s.body)).toEqual([
      { movedSoFar: 0, fromLocationId: 'rs-1', lpnId: 'p1', itemBarcode: '333000', quantity: 27, shortage: false, toLocationId: 'pick-1', allowOver: false },
      { movedSoFar: 0, fromLocationId: 'rs-1', lpnId: 'p1', itemBarcode: '333000', quantity: 27, shortage: false, toLocationId: 'pick-1', allowOver: true },
    ]);
  });

  it('makes two trips', async () => {
    state = doing();
    const user = userEvent.setup();
    open('/rf/replenish');
    await scan(user, 'Scan location', 'RS-1');
    await scan(user, 'Scan LPN', 'P1');
    await scan(user, 'Scan item', '333006');
    await scan(user, /^Quantity/, '2');
    await user.click(screen.getByRole('button', { name: 'Put these in PICK-1 and come back' }));
    replies[CONFIRM] = [{ body: doing({ movedQuantity: 12 }) }];
    await scan(user, 'Pick location', 'PICK-1');
    expect(await screen.findByText('12 of 27 EA moved')).toBeInTheDocument();
    expect(sent[0].body).toMatchObject({ quantity: 12, shortage: false });
    expect(screen.getByLabelText('Scan location')).toBeInTheDocument();
  });

  it('reports what is missing', async () => {
    state = doing({ fromLpn: null });
    const user = userEvent.setup();
    open('/rf/replenish');
    await scan(user, 'Scan location', 'RS-1');
    replies[CONFIRM] = [{ body: NONE }];
    await user.click(screen.getByRole('button', { name: 'There is none' }));
    expect(await screen.findByText('RP000001 closed. What should be there is on hold.')).toBeInTheDocument();
    expect(sent[0].body).toEqual({ movedSoFar: 0, fromLocationId: 'RS-1', lpnId: null, itemBarcode: null, quantity: 0, shortage: true, toLocationId: null, allowOver: false });
  });

  it('takes some and reports the rest missing', async () => {
    state = doing({ fromLpn: null });
    const user = userEvent.setup();
    open('/rf/replenish');
    await scan(user, 'Scan location', 'RS-1');
    await scan(user, 'Scan item', '333000');
    await scan(user, /^Quantity/, '5');
    await user.click(screen.getByRole('button', { name: 'There is no more' }));
    replies[CONFIRM] = [{ body: NONE }];
    await scan(user, 'Pick location', 'PICK-1');
    expect(await screen.findByText('Put 5 EA of SKU-CUP in PICK-1. The rest is on hold.')).toBeInTheDocument();
    expect(sent[0].body).toMatchObject({ quantity: 5, shortage: true });
  });

  it('goes back to the list when the task was cancelled meanwhile', async () => {
    state = doing({ fromLpn: null });
    const user = userEvent.setup();
    open('/rf/replenish');
    await scan(user, 'Scan location', 'RS-1');
    state = IDLE;
    replies[CONFIRM] = [{ status: 409, body: { detail: 'You are no longer doing RP000001.' } }];
    await user.click(screen.getByRole('button', { name: 'There is none' }));
    expect(await screen.findByRole('button', { name: 'Start the most urgent' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('You are no longer doing RP000001.');
  });

  it('gives a task back', async () => {
    state = doing();
    const user = userEvent.setup();
    open('/rf/replenish');
    replies['/api/rf/replenishment/tasks/t1/release'] = [{ body: IDLE }];
    await user.click(await screen.findByRole('button', { name: 'Give this task back' }));
    replies['/api/rf/replenishment/start'] = [{ status: 409, body: { detail: 'Someone else has that task, or it was called off.' } }];
    state = NONE;
    await user.click(await screen.findByRole('button', { name: /^PICK-1/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Someone else has that task');
    expect(screen.getByText('No replenishment is waiting.')).toBeInTheDocument();
  });
});
