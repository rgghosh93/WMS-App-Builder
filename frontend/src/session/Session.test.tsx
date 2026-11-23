import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { DesktopUser, Permission, SessionView } from '../api/session';

const BOSS: DesktopUser = { id: 'u1', userCode: 'BOSS', displayName: 'The Boss', permissions: ['DESKTOP', 'MASTER_DATA', 'ORDERS'], mustChangePassword: false };
const signedInAs = (user: DesktopUser): SessionView => ({ state: 'SIGNED_IN', user, setupHere: false });
const OUT: SessionView = { state: 'SIGNED_OUT', user: null, setupHere: false };
const UOM = { id: 'x1', uomCode: 'CS', description: 'Case', kind: 'CASE', status: 'ACTIVE', base: false, version: 0 };

let session: SessionView;
let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let replies: Record<string, { status?: number; body: unknown }[]>;
let expired: boolean;

beforeEach(() => {
  session = OUT;
  sent = [];
  replies = {};
  expired = false;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
      const method = init.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
        if (method === 'DELETE') {
          session = OUT;
          return json(null, 204);
        }
        const reply = replies[path]?.shift();
        return reply ? json(reply.body, reply.status) : json({ detail: 'Not found' }, 404);
      }
      if (path === '/api/session') return json(session);
      if (expired) return json({ detail: 'Sign in to continue' }, 401);
      if (path === '/api/navigation/modules') return json([]);
      if (path === '/api/uoms') return json([UOM]);
      return json({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function open(path: string) {
  render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <App />
    </MemoryRouter>,
  );
}

describe('Desktop sign-in', () => {
  it('asks who it is, then shows the page that was asked for', async () => {
    const user = userEvent.setup();
    open('/master-data/units-of-measure');
    await user.click(await screen.findByRole('button', { name: 'Sign in' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter your user ID and password');
    replies['/api/session'] = [{ status: 401, body: { detail: 'User ID or password is not right' } }, { body: BOSS }];
    await user.type(screen.getByLabelText('User ID'), 'boss');
    await user.type(screen.getByLabelText('Password'), 'wrong{Enter}');
    expect(await screen.findByText('User ID or password is not right')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveValue('');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery{Enter}');
    expect(await screen.findByRole('heading', { name: 'Units of Measure' })).toBeInTheDocument();
    expect(screen.getByText('The Boss')).toBeInTheDocument();
    expect(sent[1].body).toEqual({ userCode: 'BOSS', password: 'correct horse battery' });
  });

  it('signs out', async () => {
    const user = userEvent.setup();
    session = signedInAs(BOSS);
    open('/');
    await user.click(await screen.findByRole('button', { name: 'Sign out' }));
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(sent[0]).toMatchObject({ method: 'DELETE', path: '/api/session' });
  });

  it('goes back to sign-in when the session has ended', async () => {
    const user = userEvent.setup();
    session = signedInAs(BOSS);
    open('/outbound/orders');
    await screen.findByRole('heading', { name: 'Orders' });
    session = OUT;
    expired = true;
    await user.selectOptions(screen.getByLabelText('Status'), 'New');
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('leaves the device screens to their own sign-in', async () => {
    replies = {};
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ detail: 'Sign in to continue' }), { status: 401 })));
    open('/rf');
    expect(await screen.findByLabelText('PIN')).toBeInTheDocument();
  });

  it('shows an error with retry when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 502 })));
    open('/');
    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 502');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

describe('Connection', () => {
  it('says in plain words when nothing answers', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('Failed to fetch');
      }),
    );
    open('/');
    expect(await screen.findByRole('alert')).toHaveTextContent("Can't reach the WMS");
  });
});

describe('First administrator', () => {
  it('is created on this computer', async () => {
    const user = userEvent.setup();
    session = { state: 'SETUP', user: null, setupHere: true };
    open('/');
    await user.click(await screen.findByRole('button', { name: 'Create administrator' }));
    expect(screen.getByText('Use at least 10 characters')).toBeInTheDocument();
    await user.type(screen.getByLabelText('User ID'), 'boss');
    await user.type(screen.getByLabelText('Name'), 'The Boss');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery');
    await user.type(screen.getByLabelText('Password again'), 'correct horse');
    await user.click(screen.getByRole('button', { name: 'Create administrator' }));
    expect(screen.getByText("That isn't the same")).toBeInTheDocument();
    await user.type(screen.getByLabelText('Password again'), ' battery');
    replies['/api/session/setup'] = [{ status: 409, body: { detail: 'Taken', errors: { userCode: 'already used by another user' } } }, { body: BOSS }];
    await user.click(screen.getByRole('button', { name: 'Create administrator' }));
    expect(await screen.findByText('Already used by another user')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Create administrator' }));
    expect(await screen.findByText('The Boss')).toBeInTheDocument();
    expect(sent[1].body).toEqual({ userCode: 'BOSS', displayName: 'The Boss', password: 'correct horse battery' });
  });

  it('is not created from elsewhere', async () => {
    const user = userEvent.setup();
    session = { state: 'SETUP', user: null, setupHere: false };
    open('/');
    expect(await screen.findByText(/on the computer it runs on/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'I already have a password' }));
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });
});

describe('Passwords', () => {
  it('have to be chosen when someone else set them', async () => {
    const user = userEvent.setup();
    session = signedInAs({ ...BOSS, mustChangePassword: true });
    open('/');
    expect(await screen.findByRole('heading', { name: 'Choose your password' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Page directory' })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText('Current password'), 'given');
    await user.type(screen.getByLabelText('New password'), 'correct horse battery');
    await user.type(screen.getByLabelText('New password again'), 'correct horse battery');
    replies['/api/session/password'] = [
      { status: 400, body: { detail: "That isn't your password", errors: { currentPassword: 'not your password' } } },
      { body: BOSS },
    ];
    await user.click(screen.getByRole('button', { name: 'Save password' }));
    expect(await screen.findByText('Not your password')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save password' }));
    expect(await screen.findByRole('navigation', { name: 'Page directory' })).toBeInTheDocument();
  });

  it('can be changed', async () => {
    const user = userEvent.setup();
    session = signedInAs(BOSS);
    open('/');
    await user.click(await screen.findByRole('button', { name: 'Change password' }));
    await user.type(screen.getByLabelText('Current password'), 'correct horse battery');
    await user.type(screen.getByLabelText('New password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Save password' }));
    expect(screen.getByText('Use at least 10 characters')).toBeInTheDocument();
    await user.type(screen.getByLabelText('New password'), ' no longer');
    await user.type(screen.getByLabelText('New password again'), 'short no longer');
    replies['/api/session/password'] = [{ body: BOSS }];
    await user.click(screen.getByRole('button', { name: 'Save password' }));
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(sent[0].body).toEqual({ currentPassword: 'correct horse battery', newPassword: 'short no longer' });
  });
});

describe('Permissions', () => {
  const may = (...permissions: Permission[]) => {
    session = signedInAs({ ...BOSS, permissions: ['DESKTOP', ...permissions] });
  };

  it('let people look without changing', async () => {
    const user = userEvent.setup();
    may();
    open('/master-data/units-of-measure');
    await user.click(await screen.findByRole('button', { name: 'CS' }));
    expect(screen.queryByRole('button', { name: 'New unit' })).not.toBeInTheDocument();
    expect(screen.getByText('You can look at this but not change it.')).toBeInTheDocument();
    expect(screen.getByLabelText('Description')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
  });

  it('let people change what they may', async () => {
    const user = userEvent.setup();
    may('MASTER_DATA');
    open('/master-data/units-of-measure');
    await user.click(await screen.findByRole('button', { name: 'CS' }));
    expect(screen.getByRole('button', { name: 'New unit' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeInTheDocument();
  });

  it('leave out what people may not do', async () => {
    may('MASTER_DATA');
    open('/outbound/orders');
    await screen.findByText('No orders yet.');
    expect(screen.queryByRole('button', { name: 'New order' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create the first order' })).not.toBeInTheDocument();
  });
});
