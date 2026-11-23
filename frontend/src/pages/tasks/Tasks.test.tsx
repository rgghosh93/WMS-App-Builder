import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { NextTask, QueuedTask } from '../../api/tasks';
import { RfApp } from '../rf/RfApp';
import { TaskQueuePage } from './TaskQueuePage';
import { TaskSettingsPage } from './TaskSettingsPage';

const task = (id: string, extra: Partial<QueuedTask> = {}): QueuedTask => ({
  kind: 'COUNT', label: 'Count', id, number: id.toUpperCase(), what: 'Count BK-1', location: 'BK-1', lpn: null, priority: 7, ownPriority: 7, prioritized: false,
  assignedTo: null, worker: null, started: false, ready: true, assignable: true, createdAt: '2026-11-22T10:00:00Z', startedAt: null, link: '/inventory/counts/c1', ...extra,
});
const COUNT = task('c1');
const PUTAWAY = task('pa1', { kind: 'PUTAWAY', label: 'Put-away', what: 'Put LP1 away', location: 'RCV-1', lpn: 'LP1', priority: 2, ownPriority: 5, prioritized: true, assignedTo: { id: 'u1', name: 'Ann Lee' } });
const PACK = task('pk1', { kind: 'PACK', label: 'Pack', assignable: false, started: true, worker: 'Bob Ray' });
const PICK = task('pt1', { kind: 'PICK', label: 'Pick', what: 'Pick SO-1: 2 picks', location: 'RS-1' });
const REPLENISH = task('rp1', { kind: 'REPLENISHMENT', label: 'Replenishment', what: 'Bring 10 EA of SKU-CUP to PICK-1', location: 'RS-1' });
const SETTINGS = [
  { kind: 'COUNT', label: 'Count', priority: 7, offered: true, canBeOffered: true },
  { kind: 'PICK', label: 'Pick', priority: null, offered: true, canBeOffered: true },
  { kind: 'PACK', label: 'Pack', priority: null, offered: false, canBeOffered: false },
];

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let replies: Record<string, { status?: number; body: unknown }[]>;
let urls: string[];
let next: NextTask;

beforeEach(() => {
  sent = [];
  replies = {};
  urls = [];
  next = { doing: [], offer: COUNT, waiting: 3 };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      const method = init.method ?? 'GET';
      const body = init.body ? JSON.parse(String(init.body)) : null;
      if (path === '/api/rf/work/next') {
        sent.push({ method, path, body });
        const reply = replies[path]?.shift();
        return reply ? json(reply.body, reply.status) : json(next);
      }
      if (method !== 'GET') {
        sent.push({ method, path, body });
        const reply = replies[path]?.shift();
        return reply ? json(reply.body, reply.status) : json({ changed: (body.ids ?? []).length });
      }
      urls.push(path);
      if (path === '/api/rf/session') return json({ id: 'u1', userCode: 'ANN', displayName: 'Ann Lee' });
      if (path.startsWith('/api/rf/')) return json({ detail: 'Not here' }, 500);
      if (path === '/api/tasks/settings') return json(SETTINGS);
      if (path === '/api/tasks/summary') return json([{ kind: 'COUNT', label: 'Count', waiting: 2, assigned: 1, started: 3, oldest: null }]);
      if (path === '/api/tasks/people') {
        return json([
          { id: 'u1', name: 'Ann Lee', kinds: ['COUNT', 'PUTAWAY'], doing: ['Pick PT1'], assigned: 1 },
          { id: 'u2', name: 'Bob Ray', kinds: ['COUNT'], doing: [], assigned: 0 },
        ]);
      }
      return json({ content: [PUTAWAY, COUNT, PACK], page: 0, size: 25, totalElements: 3, totalPages: 1 });
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
        <Route path="/tasks/queue" element={<TaskQueuePage />} />
        <Route path="/tasks/settings" element={<TaskSettingsPage />} />
        <Route path="/rf/*" element={<RfApp />} />
        <Route path="/pack" element={<p>Pack station</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('The task queue', () => {
  it('shows all work, most urgent first', async () => {
    const user = userEvent.setup();
    open('/tasks/queue');
    const rows = within(await screen.findByRole('table', { name: 'Task queue' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('2was 5PA1Put-awayPut LP1 awayRCV-1LP1AssignedAnn Lee');
    expect(rows[2]).toHaveTextContent('7C1CountCount BK-1BK-1WaitingAnyone');
    expect(rows[3]).toHaveTextContent('Under wayBob Ray');
    expect(await screen.findByText('1 assigned · 3 under way')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Kind'), 'Pick');
    await user.selectOptions(screen.getByLabelText('State'), 'Not assigned');
    await user.selectOptions(screen.getByLabelText('Person'), 'Bob Ray');
    await vi.waitFor(() => expect(urls[urls.length - 1]).toContain('kind=PICK&state=UNASSIGNED&person=u2'));
  });

  it('changes priorities and who does what', async () => {
    const user = userEvent.setup();
    open('/tasks/queue');
    await user.click(await screen.findByRole('checkbox', { name: 'Select C1' }));
    const bar = within(screen.getByRole('group', { name: 'Selected tasks' }));
    await user.selectOptions(bar.getByLabelText('Priority'), '1');
    await user.click(bar.getByRole('button', { name: 'Set priority' }));
    expect(await screen.findByText('Changed the priority of 1 task')).toBeInTheDocument();
    expect(sent[0]).toMatchObject({ path: '/api/tasks/priority', body: { ids: ['c1'], priority: 1 } });

    await user.click(screen.getByRole('checkbox', { name: 'Select C1' }));
    const again = within(screen.getByRole('group', { name: 'Selected tasks' }));
    expect(again.getByRole('option', { name: 'Ann Lee (doing Pick PT1, 1 assigned)' })).toBeInTheDocument();
    await user.selectOptions(again.getByLabelText('Assign to'), 'Bob Ray');
    await user.click(again.getByRole('button', { name: 'Assign' }));
    expect(await screen.findByText('Assigned 1 task to Bob Ray')).toBeInTheDocument();
    expect(sent[1]).toMatchObject({ path: '/api/tasks/assign', body: { ids: ['c1'], userId: 'u2' } });
  });

  it('offers only people who may do all of it, and goes back to anyone', async () => {
    const user = userEvent.setup();
    open('/tasks/queue');
    await user.click(await screen.findByRole('checkbox', { name: 'Select C1' }));
    await user.click(screen.getByRole('checkbox', { name: 'Select PA1' }));
    const bar = within(screen.getByRole('group', { name: 'Selected tasks' }));
    expect(bar.queryByRole('option', { name: 'Bob Ray' })).not.toBeInTheDocument();
    await user.selectOptions(bar.getByLabelText('Priority'), 'Its own');
    await user.click(bar.getByRole('button', { name: 'Set priority' }));
    await screen.findByText('Changed the priority of 2 tasks');
    expect(sent[0].body).toEqual({ ids: ['c1', 'pa1'], priority: null });
    await user.click(screen.getByRole('checkbox', { name: 'Select PA1' }));
    await user.selectOptions(screen.getByLabelText('Assign to'), 'Nobody');
    await user.click(screen.getByRole('button', { name: 'Assign' }));
    expect(await screen.findByText('1 task for anyone again')).toBeInTheDocument();
    expect(sent[1].body).toEqual({ ids: ['pa1'], userId: null });
  });

  it('cannot assign work under way, and says why something failed', async () => {
    const user = userEvent.setup();
    open('/tasks/queue');
    await user.click(await screen.findByRole('checkbox', { name: 'Select PK1' }));
    expect(screen.queryByLabelText('Assign to')).not.toBeInTheDocument();
    await user.selectOptions(within(screen.getByRole('group', { name: 'Selected tasks' })).getByLabelText('Priority'), '3');
    replies['/api/tasks/priority'] = [{ status: 409, body: { detail: 'Some of those tasks are finished or cancelled.' } }];
    await user.click(screen.getByRole('button', { name: 'Set priority' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('finished or cancelled');
  });
});

describe('Task settings', () => {
  it('are changed', async () => {
    const user = userEvent.setup();
    open('/tasks/settings');
    await user.selectOptions(await screen.findByLabelText('Priority of Count'), '3');
    await user.click(screen.getByRole('checkbox', { name: 'Offer Pick' }));
    expect(screen.queryByRole('checkbox', { name: 'Offer Pack' })).not.toBeInTheDocument();
    replies['/api/tasks/settings'] = [{ body: SETTINGS }];
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Saved');
    expect(sent[0].body).toEqual([
      { kind: 'COUNT', priority: 3, offered: true },
      { kind: 'PICK', priority: null, offered: false },
      { kind: 'PACK', priority: null, offered: false },
    ]);
  });
});

describe('The next task', () => {
  const scan = async (label: string, value: string) => userEvent.setup().type(await screen.findByLabelText(label), `${value}{Enter}`);

  it('is started where it is, or passed over', async () => {
    const user = userEvent.setup();
    open('/rf');
    await user.click((await screen.findAllByRole('link', { name: 'Next task' }))[0]);
    expect(await screen.findByText('Count BK-1')).toBeInTheDocument();
    expect(screen.getByText('priority 7')).toBeInTheDocument();
    next = { doing: [], offer: PUTAWAY, waiting: 3 };
    await user.click(screen.getByRole('button', { name: 'Something else' }));
    expect(await screen.findByText('assigned to you')).toBeInTheDocument();
    expect(sent[sent.length - 1].body).toEqual({ skip: ['c1'] });
    await scan('Scan LPN LP1', 'LP9');
    expect(await screen.findByText('Wrong LPN. Scan LP1')).toBeInTheDocument();
    replies['/api/rf/putaway/start'] = [{ body: {} }];
    await scan('Scan LPN LP1', 'lp1');
    // On to put-away.
    expect(await screen.findByText('Not here')).toBeInTheDocument();
    expect(urls).toContain('/api/rf/putaway');
    expect(sent.find((s) => s.path === '/api/rf/putaway/start')!.body).toMatchObject({ lpnId: 'lp1' });
  });

  it('can be a pick into a new carton', async () => {
    const user = userEvent.setup();
    next = { doing: [], offer: PICK, waiting: 1 };
    open('/rf/next');
    replies['/api/rf/picking/start'] = [
      { body: { step: 'CHOOSE_CONTAINER_TYPE', newContainer: { lpnId: 'BOX9', types: [{ id: 'k1', containerTypeId: 'CTN-S', description: 'Small' }] } } },
      { body: { step: 'PICK' } },
    ];
    await scan('Scan a container to pick into', 'box9');
    await user.click(await screen.findByRole('button', { name: /CTN-S/ }));
    await vi.waitFor(() => expect(urls).toContain('/api/rf/picking'));
    expect(sent.filter((s) => s.path === '/api/rf/picking/start').map((s) => s.body)).toEqual([
      { lpnId: 'box9', containerTypeId: null, takeOver: false, taskId: 'pt1' },
      { lpnId: 'BOX9', containerTypeId: 'k1', takeOver: false, taskId: 'pt1' },
    ]);
  });

  it('is looked for again when someone else took it', async () => {
    const user = userEvent.setup();
    next = { doing: [], offer: REPLENISH, waiting: 1 };
    open('/rf/next');
    replies['/api/rf/replenishment/start'] = [{ status: 409, body: { detail: 'Someone else has that task, or it was called off.' } }];
    await user.click(await screen.findByRole('button', { name: 'Start' }).then((b) => {
      next = { doing: [], offer: null, waiting: 0 };
      return b;
    }));
    expect(await screen.findByText('There is nothing for you to do now.')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Someone else has that task');
    next = { doing: [], offer: COUNT, waiting: 1 };
    await user.click(screen.getByRole('button', { name: 'Look again' }));
    await scan('Scan location', 'BK-2');
    expect(await screen.findByText('Wrong location. Go to BK-1')).toBeInTheDocument();
  });

  it('comes after what is started', async () => {
    const user = userEvent.setup();
    next = { doing: [PACK], offer: null, waiting: 4 };
    open('/rf/next');
    expect(await screen.findByRole('heading', { name: 'Finish this first' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: /Pack PK1/ }));
    expect(screen.getByText('Pack station')).toBeInTheDocument();
  });

  it('says when everything was passed over', async () => {
    const user = userEvent.setup();
    open('/rf/next');
    await screen.findByText('Count BK-1');
    next = { doing: [], offer: null, waiting: 1 };
    await user.click(screen.getByRole('button', { name: 'Something else' }));
    expect(await screen.findByText(/You passed over the rest/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Look again' }));
    await vi.waitFor(() => expect(sent[sent.length - 1].body).toEqual({ skip: [] }));
  });
});
