import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PutawayRule, PutawayTask } from '../../api/putaway';
import { PutawayRulesPage } from './PutawayRulesPage';
import { PutawayTaskDetailPage } from './PutawayTaskDetailPage';
import { PutawayTasksPage } from './PutawayTasksPage';

const TASK: PutawayTask = {
  id: 't1',
  taskNumber: 'PA000001',
  status: 'COMPLETED',
  lpn: { id: 'c1', lpnId: 'LP1', containerType: 'GMA pallet' },
  fromLocation: { id: 'l0', locationId: 'DC1-RCV-01' },
  directedLocation: { id: 'l1', locationId: 'DC1-RS-02' },
  ruleName: 'Pallets',
  handling: 'KEEP_LPN',
  toLocation: { id: 'l3', locationId: 'DC1-RS-03' },
  overrideReason: 'BLOCKED',
  assignedTo: 'Ann Lee',
  lines: [{ item: { id: 'i1', itemId: 'SKU-BAR', description: 'Protein bar' }, quantity: 60, lotNumber: 'L1', expiryDate: '2027-06-01', status: 'AVAILABLE' }],
  totalQuantity: 60,
  skips: [{ location: { id: 'l2', locationId: 'DC1-RS-01' }, reason: 'FULL', skippedBy: 'Ann Lee', skippedAt: '2026-11-22T10:11:00Z' }],
  createdAt: '2026-11-22T10:00:00Z',
  startedAt: '2026-11-22T10:10:00Z',
  completedAt: '2026-11-22T10:12:00Z',
};
const RULES: PutawayRule[] = [
  {
    id: 'r1', sequence: 10, name: 'Bars home', status: 'ACTIVE', item: TASK.lines[0].item, containerCategory: null, stockStatus: null,
    strategy: 'FIXED', location: { id: 'l3', locationId: 'DC1-RS-03' }, zone: null, handling: 'EMPTY_LPN', version: 4,
  },
  {
    id: 'r2', sequence: 20, name: 'Pallets', status: 'INACTIVE', item: null, containerCategory: 'PALLET', stockStatus: null,
    strategy: 'ZONE', location: null, zone: { id: 'z1', code: 'RESERVE', name: 'Reserve' }, handling: 'KEEP_LPN', version: 0,
  },
];

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let reply: { status: number; body: unknown } | null;
let urls: string[];

beforeEach(() => {
  sent = [];
  urls = [];
  reply = null;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
      const method = init.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
        return reply ? json(reply.body, reply.status) : method === 'DELETE' ? json(null, 204) : json(RULES[0]);
      }
      urls.push(path);
      if (path.startsWith('/api/locations')) return json({ content: [{ id: 'l9', locationId: 'DC1-BK-09' }], page: 0, size: 8, totalElements: 1, totalPages: 1 });
      if (path.startsWith('/api/zones')) return json([{ id: 'z1', function: 'PUTAWAY', code: 'RESERVE', name: 'Reserve', status: 'ACTIVE', priority: null, allocatable: true, locationCount: 3, version: 0 }]);
      if (path === '/api/putaway/rules') return json(RULES);
      if (path.startsWith('/api/putaway/tasks?')) return json({ content: [TASK], page: 0, size: 25, totalElements: 1, totalPages: 1 });
      return json(TASK);
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
        <Route path="/putaway/tasks" element={<PutawayTasksPage />} />
        <Route path="/putaway/tasks/:id" element={<PutawayTaskDetailPage />} />
        <Route path="/putaway/rules" element={<PutawayRulesPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Put-away tasks', () => {
  it('lists tasks and filters by status', async () => {
    const user = userEvent.setup();
    renderAt('/putaway/tasks');
    const row = (await screen.findByRole('link', { name: 'PA000001' })).closest('tr')!;
    expect(row).toHaveTextContent('DC1-RS-03not DC1-RS-02');
    expect(row).toHaveTextContent('Put away');
    await user.selectOptions(screen.getByLabelText('Status'), 'Waiting');
    await vi.waitFor(() => expect(urls[urls.length - 1]).toContain('status=OPEN'));
  });

  it('shows where it was sent, where it went, and what was passed over', async () => {
    renderAt('/putaway/tasks/t1');
    expect(await screen.findByText("in its LPN · instead: can't reach it")).toBeInTheDocument();
    expect(screen.getByText(/it is full \(Ann Lee\)/)).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Contents' })).getByText('60 EA')).toBeInTheDocument();
  });
});

describe('Put-away rules', () => {
  it('lists rules in the order they are tried', async () => {
    renderAt('/putaway/rules');
    const rows = within(await screen.findByRole('table', { name: 'Put-away rules' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('10Bars homeonly SKU-BARDC1-RS-03EmptiedActive');
    expect(rows[2]).toHaveTextContent('20PalletsPallet LPNsZone RESERVEKeptInactive');
  });

  it('creates a rule', async () => {
    const user = userEvent.setup();
    renderAt('/putaway/rules');
    await user.click(await screen.findByRole('button', { name: 'New rule' }));
    expect(screen.getByLabelText('Order')).toHaveValue(30);
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(screen.getByText('Enter a name')).toBeInTheDocument();
    expect(screen.getAllByText('Choose a zone')).toHaveLength(2);
    expect(await screen.findByRole('option', { name: 'RESERVE · Reserve' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Name'), 'Damaged');
    await user.selectOptions(screen.getByLabelText('Stock status'), 'Damaged');
    await user.selectOptions(screen.getByLabelText('How to choose'), 'Fixed location');
    await user.type(screen.getByLabelText('Location'), 'bk');
    await user.click(await screen.findByRole('option', { name: 'DC1-BK-09' }));
    expect(urls[urls.length - 1]).toContain('type=STORAGE');
    reply = { status: 400, body: { detail: 'Some fields need attention', errors: { name: 'already used by another rule' } } };
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(await screen.findByText('Already used by another rule')).toBeInTheDocument();
    reply = null;
    await user.click(screen.getByRole('button', { name: 'Create rule' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Created rule');
    expect(sent[1].body).toMatchObject({
      sequence: 30, name: 'Damaged', stockStatus: 'DAMAGED', strategy: 'FIXED', locationId: 'l9', zoneId: null, itemId: null, handling: 'KEEP_LPN',
    });
  });

  it('edits and deletes a rule', async () => {
    const user = userEvent.setup();
    renderAt('/putaway/rules');
    await user.click(await screen.findByRole('button', { name: 'Bars home' }));
    await user.selectOptions(screen.getByLabelText('The LPN'), 'Keep it in its LPN');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByRole('status');
    expect(sent[0]).toMatchObject({ method: 'PUT', path: '/api/putaway/rules/r1', body: { version: 4, handling: 'KEEP_LPN', itemId: 'i1', locationId: 'l3' } });
    await user.click(await screen.findByRole('button', { name: 'Pallets' }));
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(within(screen.getByRole('group', { name: 'Confirm delete' })).getByRole('button', { name: 'Delete' }));
    await vi.waitFor(() => expect(sent[1]).toMatchObject({ method: 'DELETE', path: '/api/putaway/rules/r2' }));
  });
});
