import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BuilderNotification } from '../../api/builder';
import { NOTIFY_POLL_MS, NotificationBell } from './NotificationBell';

const FINISHED: BuilderNotification = {
  id: 'c1',
  number: 'CR-000001',
  title: 'Add staging locations',
  kind: 'FINISHED',
  message: 'The agent has finished. Have a look at what it did.',
  at: '2026-11-23T10:00:00Z',
};
const QUESTIONS: BuilderNotification = {
  id: 'c2',
  number: 'CR-000002',
  title: 'Record temperatures',
  kind: 'QUESTIONS',
  message: 'The agent has questions for you before it builds anything.',
  at: '2026-11-23T11:00:00Z',
};

let answer: { status: number; body: unknown };
let shown: { title: string; body?: string }[];

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

function show() {
  render(
    <MemoryRouter initialEntries={['/']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <NotificationBell />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  answer = { status: 200, body: [FINISHED] };
  shown = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(answer.body), { status: answer.status })),
  );
  class FakeNotification {
    static permission: NotificationPermission = 'granted';
    static requestPermission = vi.fn(async () => 'granted' as NotificationPermission);
    onclick: (() => void) | null = null;
    constructor(title: string, options?: { body?: string }) {
      shown.push({ title, body: options?.body });
    }
    close() {}
  }
  vi.stubGlobal('Notification', FakeNotification);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('App Builder notifications', () => {
  it('counts what needs you and takes you to it', async () => {
    const user = userEvent.setup();
    show();
    const bell = await screen.findByRole('button', { name: 'App Builder: 1 need you' });
    expect(bell).toHaveTextContent('1');
    await user.click(bell);
    const panel = screen.getByRole('dialog', { name: 'App Builder notifications' });
    expect(panel).toHaveTextContent('Finished · CR-000001Add staging locationsThe agent has finished.');
    await user.click(within(panel).getByRole('link', { name: /Add staging locations/ }));
    expect(screen.getByTestId('where')).toHaveTextContent('/builder/c1');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('tells you on this computer about what is new, not what was there already', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    show();
    await screen.findByRole('button', { name: 'App Builder: 1 need you' });
    expect(shown).toEqual([]);

    answer = { status: 200, body: [QUESTIONS, FINISHED] };
    await act(async () => {
      await vi.advanceTimersByTimeAsync(NOTIFY_POLL_MS);
    });
    await screen.findByRole('button', { name: 'App Builder: 2 need you' });
    expect(shown).toEqual([
      { title: 'CR-000002 · Needs your answers', body: 'Record temperatures\nThe agent has questions for you before it builds anything.' },
    ]);
  });

  it('is not there where the App Builder is off', async () => {
    answer = { status: 404, body: { detail: 'The App Builder is switched off here' } };
    show();
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.queryByRole('button', { name: /App Builder/ })).not.toBeInTheDocument();
  });
});
