import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChangeDetail, ChangeSummary } from '../../api/builder';
import { AppBuilderPage } from './AppBuilderPage';

const SUMMARY: ChangeSummary = {
  id: 'c1',
  number: 'CR-000001',
  title: 'Ask for the temperature when receiving',
  status: 'DRAFTING',
  working: false,
  requestedBy: 'BOSS',
  createdAt: '2026-11-23T10:00:00Z',
  updatedAt: '2026-11-23T10:05:00Z',
};

function detail(working: boolean, extra: Partial<ChangeDetail> = {}): ChangeDetail {
  return {
    change: { ...SUMMARY, working },
    entries: [
      { seq: 1, role: 'PERSON', blocks: [{ type: 'text', text: 'Ask for the temperature when receiving chilled items' }], model: null, at: '' },
      {
        seq: 2,
        role: 'AGENT',
        blocks: [
          { type: 'text', text: 'Let me look at receiving.' },
          { type: 'tool_call', id: 't1', name: 'read_file', input: { path: 'frontend/src/pages/rf/Receiving.tsx' } },
          { type: 'tool_call', id: 't2', name: 'write_file', input: { path: '.github/x.yml', content: 'x' } },
        ],
        model: 'claude-opus-5-5',
        at: '',
      },
      {
        seq: 3,
        role: 'RESULTS',
        blocks: [
          { type: 'tool_result', callId: 't1', content: '1\timport', error: false },
          { type: 'tool_result', callId: 't2', content: "can't be changed by the App Builder", error: true },
        ],
        model: null,
        at: '',
      },
      { seq: 4, role: 'AGENT', blocks: [{ type: 'text', text: 'Should this apply to every owner?' }], model: 'claude-opus-5-5', at: '' },
    ],
    files: ['M frontend/src/pages/rf/Receiving.tsx'],
    branch: 'change/cr-000001',
    lastProblem: null,
    inputTokens: 1200,
    outputTokens: 80,
    accepted: null,
    preview: null,
    questions: null,
    discarded: null,
    developer: true,
    ...extra,
  };
}

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let current: ChangeDetail;
let seen: string[];

function show(path: string) {
  render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/builder" element={<AppBuilderPage />} />
        <Route path="/builder/:id" element={<AppBuilderPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  sent = [];
  seen = [];
  current = detail(false);
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      const method = init.method ?? 'GET';
      if (path === '/api/builder/status') return json({ enabled: true, model: 'm', checks: true, previews: true, acceptBranch: 'builder' });
      if (path.endsWith('/seen')) {
        seen.push(path);
        return new Response(null, { status: 204 });
      }
      if (method === 'GET') return json(path === '/api/builder/changes' ? [{ ...SUMMARY, working: current.change.working, status: current.change.status }] : current);
      sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
      if (path.endsWith('/messages')) {
        current = detail(true);
        return json(current, 202);
      }
      if (path.endsWith('/stop')) return json(current);
      if (path.endsWith('/preview') && method === 'POST') {
        current = detail(false, { preview: { state: 'STARTING', url: 'http://127.0.0.1:8090', upToDate: true, step: 'Building the screens', problem: null, since: '', signIn: null } });
        return json(current, 202);
      }
      if (path.endsWith('/preview') && method === 'DELETE') {
        current = detail(false);
        return json(current);
      }
      if (path.endsWith('/answers')) {
        current = detail(true);
        return json(current, 202);
      }
      if (path.endsWith('/discard')) {
        current = detail(false, { change: { ...SUMMARY, status: 'DISCARDED' }, discarded: { at: '2026-11-23T12:00:00Z', by: 'BOSS', reason: 'Not needed' } });
        return json(current);
      }
      if (path.endsWith('/reopen')) {
        current = detail(false);
        return json(current);
      }
      if (path.endsWith('/accept')) {
        current = detail(false, { change: { ...SUMMARY, status: 'ACCEPTED' }, accepted: { at: '2026-11-23T11:00:00Z', by: 'BOSS', into: 'builder', upToDate: true } });
        return json(current);
      }
      return json(detail(true), 201);
    }),
  );
});

afterEach(() => {
  window.localStorage.clear();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('App Builder', () => {
  it('starts a change request from what someone describes', async () => {
    const user = userEvent.setup();
    show('/builder');
    expect(await screen.findByRole('link', { name: /Ask for the temperature/ })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Describe the change'), 'Add a carrier field to orders');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(sent[0]).toEqual({ method: 'POST', path: '/api/builder/changes', body: { text: 'Add a carrier field to orders' } });
    expect(await screen.findByRole('heading', { name: 'Ask for the temperature when receiving' })).toBeInTheDocument();
  });

  it('shows the conversation in plain words, with what the agent did tucked away', async () => {
    const user = userEvent.setup();
    show('/builder/c1');
    let conversation = await screen.findByRole('list', { name: 'Conversation' });
    // Even a developer sees the business view first.
    expect(within(conversation).getByText('Looked through the WMS')).toBeInTheDocument();
    expect(within(conversation).getByText('Made changes')).toBeInTheDocument();
    expect(screen.queryByText(/Receiving\.tsx/)).not.toBeInTheDocument();
    expect(screen.queryByText('Developer details')).not.toBeInTheDocument();

    await user.click(screen.getByLabelText(/Developer mode/));
    conversation = screen.getByRole('list', { name: 'Conversation' });
    const turns = within(conversation).getAllByRole('listitem', { name: '' }).filter((li) => li.classList.contains('turn'));
    expect(turns).toHaveLength(3);
    expect(turns[0]).toHaveTextContent('Ask for the temperature when receiving chilled items');
    expect(turns[1]).toHaveTextContent('Let me look at receiving.');
    expect(within(turns[1]).getByText('2 steps: read, wrote')).toBeInTheDocument();
    expect(within(turns[1]).getByText('Wrote .github/x.yml').closest('li')).toHaveClass('step--refused');
    expect(turns[2]).toHaveTextContent('Should this apply to every owner?');
    const files = screen.getByRole('list', { name: 'Files changed' });
    expect(within(files).getByRole('listitem')).toHaveTextContent('ChangedReceiving.tsxfrontend/src/pages/rf');
    expect(screen.getByText('1: 1 changed')).toBeInTheDocument();
  });

  it('carries the conversation on, and waits while the agent works', async () => {
    const user = userEvent.setup();
    show('/builder/c1');
    await user.type(await screen.findByLabelText('Reply to the agent'), 'Only for chilled items');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(sent[0]).toEqual({ method: 'POST', path: '/api/builder/changes/c1/messages', body: { text: 'Only for chilled items' } });
    expect(await screen.findByText('The agent is working on this…')).toBeInTheDocument();
    expect(screen.getByLabelText('Reply to the agent')).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Stop' }));
    expect(sent[1]).toEqual({ method: 'POST', path: '/api/builder/changes/c1/stop', body: null });
  });

  it('says why the agent stopped', async () => {
    current = detail(false, { lastProblem: 'Claude is busy or the account’s limit is reached. Try again in a minute.' });
    show('/builder/c1');
    expect(await screen.findByRole('alert')).toHaveTextContent('Claude is busy');
  });

  it('previews the change, and says how far it has got', async () => {
    const user = userEvent.setup();
    show('/builder/c1');
    await user.click(await screen.findByRole('button', { name: 'Preview' }));
    expect(sent[0]).toEqual({ method: 'POST', path: '/api/builder/changes/c1/preview', body: null });
    expect(await screen.findByText(/Starting the preview: Building the screens/)).toBeInTheDocument();
    current = detail(false, {
      preview: {
        state: 'RUNNING',
        url: 'http://127.0.0.1:8090',
        upToDate: false,
        step: null,
        problem: null,
        since: '',
        signIn: { userCode: 'BOSS', password: 'abcd-efgh-jkmn-pqrs', note: null },
      },
    });
    expect(await screen.findByRole('link', { name: /127\.0\.0\.1:8090/ }, { timeout: 4000 })).toHaveAttribute('href', 'http://127.0.0.1:8090');
    expect(screen.getByText(/restart it to see the latest/)).toBeInTheDocument();
    expect(screen.getByText(/Sign in as/)).toHaveTextContent('Sign in as BOSS with password');
    expect(screen.getByLabelText('Preview password')).not.toHaveTextContent('abcd');
    await user.click(screen.getByRole('button', { name: 'Show' }));
    expect(screen.getByLabelText('Preview password')).toHaveTextContent('abcd-efgh-jkmn-pqrs');
    await user.click(screen.getByRole('button', { name: 'Stop preview' }));
    expect(sent.at(-1)).toEqual({ method: 'DELETE', path: '/api/builder/changes/c1/preview', body: null });
    expect(await screen.findByRole('button', { name: 'Preview' })).toBeInTheDocument();
  });

  it('accepts the change once it is confirmed', async () => {
    const user = userEvent.setup();
    show('/builder/c1');
    await user.click(await screen.findByRole('button', { name: 'Accept' }));
    expect(screen.getByRole('group', { name: 'Confirm accept' })).toHaveTextContent('puts this change into builder');
    expect(sent).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Accept change' }));
    expect(sent[0]).toEqual({ method: 'POST', path: '/api/builder/changes/c1/accept', body: null });
    expect(await screen.findByText(/Accepted into builder by BOSS/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Accept' })).toBeDisabled();
  });

  it('has nothing to preview or accept before the agent changes anything', async () => {
    current = detail(false, { files: [] });
    show('/builder/c1');
    expect(await screen.findByRole('button', { name: 'Accept' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Preview' })).toBeDisabled();
  });

  const ASKING: Partial<ChangeDetail> = {
    files: [],
    questions: {
      callId: 'q1',
      intro: 'Two things decide how staging works.',
      questions: [
        {
          question: 'How should stock get into staging?',
          options: [
            { label: 'Inventory Move', description: null },
            { label: 'Put-away', description: 'As a stop on the way to storage' },
          ],
          multiple: true,
          recommended: 'Inventory Move',
        },
        { question: 'Should orders pick from staging?', options: [{ label: 'Yes', description: null }, { label: 'No', description: null }], multiple: false, recommended: 'No' },
        { question: 'Anything else?', options: [], multiple: false, recommended: null },
      ],
    },
  };

  it('opens the agent\'s questions as a form, with its recommendations picked', async () => {
    const user = userEvent.setup();
    current = detail(false, ASKING);
    show('/builder/c1');
    const form = await screen.findByRole('dialog', { name: 'The agent has a few questions' });
    expect(form).toHaveTextContent('Two things decide how staging works.');
    expect(within(form).getByRole('checkbox', { name: /Inventory Move/ })).toBeChecked();
    expect(within(form).getByRole('radio', { name: /No/ })).toBeChecked();
    const send = within(form).getByRole('button', { name: 'Send answers' });
    expect(send).toBeDisabled();

    await user.click(within(form).getByRole('checkbox', { name: /Put-away/ }));
    await user.click(within(form).getByRole('radio', { name: /Something else/ }));
    expect(within(form).getByRole('radio', { name: /No/ })).not.toBeChecked();
    await user.type(within(form).getByLabelText('Your answer to question 2'), 'Only when short');
    await user.type(within(form).getByLabelText('Anything else?'), 'Count them');
    await user.click(send);

    expect(sent[0]).toEqual({
      method: 'POST',
      path: '/api/builder/changes/c1/answers',
      body: {
        callId: 'q1',
        answers: [
          { question: 0, chosen: ['Inventory Move', 'Put-away'], other: null },
          { question: 1, chosen: [], other: 'Only when short' },
          { question: 2, chosen: [], other: 'Count them' },
        ],
      },
    });
    expect(await screen.findByText('The agent is working on this…')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('can be put aside and opened again, or answered in the chat', async () => {
    const user = userEvent.setup();
    current = detail(false, ASKING);
    show('/builder/c1');
    const form = await screen.findByRole('dialog');
    await user.click(within(form).getByRole('button', { name: 'Answer in the chat instead' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText(/waiting for your answers/)).toBeInTheDocument();
    expect(screen.getByLabelText('Reply to the agent')).toHaveAttribute('placeholder', 'Or answer the questions in your own words');
    await user.click(screen.getByRole('button', { name: 'Answer' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes a change request with a reason, and opens it again', async () => {
    const user = userEvent.setup();
    show('/builder/c1');
    await user.click(await screen.findByRole('button', { name: 'Discard' }));
    const confirm = screen.getByRole('group', { name: 'Confirm close' });
    await user.type(within(confirm).getByLabelText('Why it is being closed'), 'Not needed');
    await user.click(within(confirm).getByRole('button', { name: 'Close change request' }));
    expect(sent[0]).toEqual({ method: 'POST', path: '/api/builder/changes/c1/discard', body: { reason: 'Not needed' } });

    expect(await screen.findByText(/Closed by BOSS on .*: Not needed/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Reply to the agent')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument();
    expect(await screen.findByRole('list', { name: 'Closed change requests' })).toHaveTextContent('Discarded');

    await user.click(screen.getByRole('button', { name: 'Reopen' }));
    expect(sent[1]).toEqual({ method: 'POST', path: '/api/builder/changes/c1/reopen', body: null });
    expect(await screen.findByLabelText('Reply to the agent')).toBeInTheDocument();
  });

  it('shows the agent\'s summary as a card that opens a summary page', async () => {
    const user = userEvent.setup();
    const base = detail(false);
    current = {
      ...base,
      entries: [
        ...base.entries,
        {
          seq: 5,
          role: 'AGENT',
          blocks: [
            { type: 'text', text: 'Done. **Staging** is ready:\n\n- one\n- two' },
            {
              type: 'tool_call',
              id: 'r1',
              name: 'report_change',
              input: {
                headline: 'Staging is a new location type, ready to try.',
                changes: [
                  { area: 'Locations page', what: 'Staging is in the type list.' },
                  { area: 'Handheld Move', what: 'Stock can be moved into staging.' },
                ],
                try_it: ['Create a staging location', 'Move an LPN into it'],
                checklist: [
                  { item: 'Staging can be created', result: 'passed' },
                  { item: 'Moved on a real device', result: 'not_checked', note: 'No device here' },
                ],
                left_out: ['Counting staging locations'],
              },
            },
          ],
          model: 'm',
          at: '',
        },
      ],
    };
    show('/builder/c1');
    const card = (await screen.findByText('Change summary')).closest('.summary-card') as HTMLElement;
    expect(card).toHaveTextContent('Staging is a new location type, ready to try.');
    expect(card).toHaveTextContent('2 parts of the WMS changed · 1 passed, 1 not checked');
    // The message around it is formatted, not shown with its asterisks.
    expect(screen.getByText('Staging', { selector: 'strong' })).toBeInTheDocument();
    expect(screen.getByText('one', { selector: 'li' })).toBeInTheDocument();

    await user.click(within(card).getByRole('button', { name: 'Open summary' }));
    const page = screen.getByRole('dialog', { name: 'Ask for the temperature when receiving' });
    expect(within(page).getByRole('heading', { name: 'What changed' })).toBeInTheDocument();
    expect(page).toHaveTextContent('Locations pageStaging is in the type list.');
    expect(within(page).getByRole('heading', { name: 'How to try it' })).toBeInTheDocument();
    expect(page).toHaveTextContent('Moved on a real deviceNo device hereNot checked');
    expect(page).toHaveTextContent('Counting staging locations');
    await user.click(within(page).getAllByRole('button', { name: 'Close' }).at(-1)!);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    // Steps list doesn't show the summary as a step.
    expect(screen.queryByText(/report_change/)).not.toBeInTheDocument();
  });

  it('tells the App Builder you have looked, once the agent has finished', async () => {
    show('/builder/c1');
    await screen.findByRole('list', { name: 'Conversation' });
    await waitFor(() => expect(seen).toEqual(['/api/builder/changes/c1/seen']));
  });

  it('shows people who aren\'t developers no developer mode or details', async () => {
    current = detail(false, { developer: false, files: [], branch: null as unknown as string });
    show('/builder/c1');
    await screen.findByRole('list', { name: 'Conversation' });
    expect(screen.getByText('Looked through the WMS')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Developer mode/)).not.toBeInTheDocument();
    expect(screen.queryByText('Developer details')).not.toBeInTheDocument();
  });

  it('says what the agent is doing in its own plain words', async () => {
    const base = detail(true, { developer: false });
    current = {
      ...base,
      entries: [
        base.entries[0],
        {
          seq: 2,
          role: 'AGENT',
          blocks: [
            { type: 'tool_call', id: 'p1', name: 'read_file', input: { purpose: 'Checking how locations are set up today' } },
            { type: 'tool_call', id: 'p2', name: 'search_code', input: { purpose: 'Checking how locations are set up today' } },
            { type: 'tool_call', id: 'p3', name: 'edit_file', input: { purpose: 'Adding Staging to the location types' } },
            { type: 'tool_call', id: 'p4', name: 'run_checks', input: { purpose: 'Making sure receiving still works', which: 'backend' } },
          ],
          model: null,
          at: '',
        },
        {
          seq: 3,
          role: 'RESULTS',
          blocks: [{ type: 'tool_result', callId: 'p4', content: 'passed', error: false }],
          model: null,
          at: '',
        },
      ],
    };
    show('/builder/c1');
    const conversation = await screen.findByRole('list', { name: 'Conversation' });
    expect(conversation).toHaveTextContent(
      'Checking how locations are set up todayAdding Staging to the location typesMaking sure receiving still worksThe checks passed.',
    );
    expect(screen.getByRole('status')).toHaveTextContent('Working on it: Making sure receiving still works…');
  });

  it('puts a run of steps in one bubble, naming the parts of the WMS they were in', async () => {
    const user = userEvent.setup();
    const base = detail(false, { developer: false });
    const step = (seq: number, name: string, area: string) => ({
      seq,
      role: 'AGENT' as const,
      blocks: [{ type: 'tool_call' as const, id: `s${seq}`, name, input: { area } }],
      model: null,
      at: '',
    });
    current = {
      ...base,
      entries: [
        base.entries[0],
        step(2, 'read_file', 'locations'),
        step(3, 'search_code', 'handheld screens'),
        step(4, 'read_file', 'locations'),
        step(5, 'edit_file', 'locations'),
        step(6, 'edit_file', 'handheld screens'),
        ...Array.from({ length: 6 }, (_, i) => ({
          seq: 10 + i,
          role: 'AGENT' as const,
          blocks: [{ type: 'tool_call' as const, id: `t${i}`, name: 'read_file', input: { purpose: `Checking rule ${i + 1}` } }],
          model: null,
          at: '',
        })),
        { seq: 20, role: 'AGENT', blocks: [{ type: 'text', text: 'Here is where I got to.' }], model: null, at: '' },
      ],
    };
    show('/builder/c1');
    const conversation = await screen.findByRole('list', { name: 'Conversation' });
    const bubbles = within(conversation).getAllByRole('listitem').filter((li) => li.classList.contains('turn'));
    expect(bubbles).toHaveLength(3);
    expect(bubbles[1]).toHaveTextContent('Looked at locations and handheld screens');
    expect(bubbles[1]).toHaveTextContent('Changed locations and handheld screens');
    expect(bubbles[1]).toHaveTextContent('Checking rule 4');
    expect(bubbles[1]).not.toHaveTextContent('Checking rule 5');
    await user.click(within(bubbles[1]).getByRole('button', { name: 'Show 2 more' }));
    expect(bubbles[1]).toHaveTextContent('Checking rule 6');
    expect(bubbles[2]).toHaveTextContent('Here is where I got to.');
  });
});
