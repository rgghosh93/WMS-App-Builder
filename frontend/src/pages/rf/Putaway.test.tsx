import { render, screen, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PutawayState, PutawayTask } from '../../api/putaway';
import { RfApp } from './RfApp';

const USER = { id: 'u1', userCode: 'ANN', displayName: 'Ann Lee' };
const TASK: PutawayTask = {
  id: 't1',
  taskNumber: 'PA000001',
  status: 'IN_PROGRESS',
  lpn: { id: 'c1', lpnId: 'LP1', containerType: 'GMA pallet' },
  fromLocation: { id: 'l0', locationId: 'DC1-RCV-01' },
  directedLocation: { id: 'l1', locationId: 'DC1-RS-01' },
  ruleName: 'Pallets',
  handling: 'KEEP_LPN',
  toLocation: null,
  overrideReason: null,
  assignedTo: 'Ann Lee',
  lines: [{ item: { id: 'i1', itemId: 'SKU-BAR', description: 'Protein bar' }, quantity: 60, lotNumber: 'L1', expiryDate: null, status: 'ON_HOLD' }],
  totalQuantity: 60,
  skips: [],
  createdAt: '2026-11-22T10:00:00Z',
  startedAt: '2026-11-22T10:10:00Z',
  completedAt: null,
};
const WAITING: PutawayState = { task: null, waitingCount: 1, waiting: [{ ...TASK, status: 'OPEN', directedLocation: null }] };
const NONE: PutawayState = { task: null, waitingCount: 0, waiting: [] };
const WORKING: PutawayState = { task: TASK, waitingCount: 0, waiting: [] };

type Reply = { status?: number; body: unknown };
let posts: { path: string; body: Record<string, unknown> }[];
let replies: Record<string, Reply[]>;
let state: PutawayState;

beforeEach(() => {
  posts = [];
  replies = {};
  state = WAITING;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      if ((init.method ?? 'GET') === 'GET') return json(path === '/api/rf/session' ? USER : state);
      posts.push({ path, body: JSON.parse(String(init.body)) });
      const reply = replies[path]?.shift();
      return reply ? json(reply.body, reply.status) : json({ detail: 'Not found' }, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const API = '/api/rf/putaway';
const CONFIRM = `${API}/tasks/t1/confirm`;

function open() {
  render(
    <MemoryRouter initialEntries={['/rf/putaway']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/rf/*" element={<RfApp />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function scan(user: UserEvent, label: string, value: string) {
  await user.type(await screen.findByLabelText(label), `${value}{Enter}`);
}

describe('Put-away', () => {
  it('takes an LPN to its directed location', async () => {
    const user = userEvent.setup();
    open();
    expect(await screen.findByLabelText('Waiting')).toHaveTextContent('LP1DC1-RCV-01 · 60 EA');
    replies[`${API}/start`] = [{ body: WORKING }];
    await scan(user, 'LPN', 'lp1');
    expect(await screen.findByText('DC1-RS-01', { selector: '.rf-card__big' })).toBeInTheDocument();
    expect(screen.getByText(/lot L1 · On hold/)).toBeInTheDocument();
    expect(screen.getByLabelText('Scan location')).toHaveFocus();
    replies[CONFIRM] = [{ body: NONE }];
    await scan(user, 'Scan location', 'dc1-rs-01');
    expect(await screen.findByRole('status')).toHaveTextContent('LP1 put in DC1-RS-01.');
    expect(screen.getByText('No LPNs are waiting to be put away.')).toBeInTheDocument();
    expect(posts[1].body).toEqual({ locationId: 'dc1-rs-01', reason: null, allowOver: false, handling: null });
  });

  it('asks why when another location is scanned, then about room', async () => {
    const user = userEvent.setup();
    state = WORKING;
    open();
    replies[CONFIRM] = [
      { status: 409, body: { detail: 'LP1 should go to DC1-RS-01, not DC1-RS-03.', errors: { directed: 'DC1-RS-01' } } },
      { status: 409, body: { detail: 'DC1-RS-03 has no room for another LPN (it holds 1).', errors: { capacity: 'x' } } },
      { body: NONE },
    ];
    await scan(user, 'Scan location', 'DC1-RS-03');
    const why = await screen.findByRole('group', { name: 'Another location' });
    await user.click(within(why).getByRole('button', { name: 'Another reason' }));
    const over = await screen.findByRole('group', { name: 'No room' });
    expect(over).toHaveTextContent('has no room');
    await user.click(within(over).getByRole('button', { name: 'Put it there anyway' }));
    await screen.findByRole('status');
    expect(posts[2].body).toMatchObject({ locationId: 'DC1-RS-03', reason: 'OTHER', allowOver: true });
  });

  it('is sent somewhere else when the location cannot be used', async () => {
    const user = userEvent.setup();
    state = WORKING;
    open();
    await user.click(await screen.findByRole('button', { name: "Can't use this location" }));
    replies[`${API}/tasks/t1/redirect`] = [{ body: { ...WORKING, task: { ...TASK, directedLocation: null } } }];
    await user.click(screen.getByRole('button', { name: 'It is full' }));
    expect(await screen.findByText('No location with room was found')).toBeInTheDocument();
    expect(posts[0].body).toEqual({ locationId: 'DC1-RS-01', reason: 'FULL' });
    expect(screen.getByLabelText('Scan location')).toHaveFocus();
  });

  it('can empty the LPN instead of keeping it', async () => {
    const user = userEvent.setup();
    state = WORKING;
    open();
    await user.click(await screen.findByRole('button', { name: 'Empty it into the location instead' }));
    expect(screen.getByText('Empty it into')).toBeInTheDocument();
    expect(screen.getByLabelText('Scan location')).toHaveFocus();
    replies[CONFIRM] = [{ body: NONE }];
    await scan(user, 'Scan location', 'DC1-RS-01');
    expect(await screen.findByRole('status')).toHaveTextContent('LP1 emptied into DC1-RS-01.');
    expect(posts[0].body).toMatchObject({ handling: 'EMPTY_LPN' });
  });

  it('puts an LPN back', async () => {
    const user = userEvent.setup();
    state = WORKING;
    open();
    replies[`${API}/tasks/t1/release`] = [{ body: WAITING }];
    await user.click(await screen.findByRole('button', { name: 'Put it back at DC1-RCV-01' }));
    expect(await screen.findByRole('status')).toHaveTextContent('LP1 is back at DC1-RCV-01.');
  });

  it('asks before taking over, and says why an LPN is refused', async () => {
    const user = userEvent.setup();
    open();
    replies[`${API}/start`] = [
      { status: 400, body: { detail: 'NOPE is not a known LPN', errors: { lpnId: 'x' } } },
      { status: 409, body: { detail: 'Bob Ray is putting LP1 away.', errors: { assignedTo: 'Bob Ray' } } },
      { body: WORKING },
    ];
    await scan(user, 'LPN', 'nope');
    expect(await screen.findByRole('alert')).toHaveTextContent('NOPE is not a known LPN');
    await scan(user, 'LPN', 'lp1');
    await user.click(await screen.findByRole('button', { name: 'Take over LP1' }));
    await screen.findByLabelText('Scan location');
    expect(posts[2].body).toEqual({ lpnId: 'lp1', takeOver: true });
  });

  it('notices when someone else has taken over', async () => {
    const user = userEvent.setup();
    state = WORKING;
    open();
    await screen.findByLabelText('Scan location');
    state = NONE;
    replies[CONFIRM] = [{ status: 409, body: { detail: 'You are no longer putting LP1 away.' } }];
    await scan(user, 'Scan location', 'DC1-RS-01');
    expect(await screen.findByRole('alert')).toHaveTextContent('You are no longer putting LP1 away.');
    expect(screen.getByLabelText('LPN')).toBeInTheDocument();
  });
});
