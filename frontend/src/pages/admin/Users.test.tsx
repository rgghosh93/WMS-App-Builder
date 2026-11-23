import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { pinError, randomPin, type User } from '../../api/users';
import { UsersPage } from './UsersPage';

const ANN: User = {
  id: 'u1',
  userCode: 'ANN',
  displayName: 'Ann Lee',
  status: 'ACTIVE',
  roles: [{ id: 'op', name: 'Operator' }],
  hasPin: true,
  hasPassword: false,
  lockedUntil: '2026-11-22T10:05:00Z',
  devices: 2,
  lastSignInAt: '2026-11-22T10:00:00Z',
  inHand: ['Picking PT000009'],
  version: 3,
};
const NEW: User = { ...ANN, id: 'u2', userCode: 'BOB', displayName: 'Bob Ray', lockedUntil: null, devices: 0, lastSignInAt: null, inHand: [], roles: [], hasPassword: true, version: 0 };
const ROLES = [
  { id: 'adm', name: 'Administrator', description: 'Everything', administrator: true, permissions: [], userCount: 1, version: 0 },
  { id: 'op', name: 'Operator', description: null, administrator: false, permissions: [], userCount: 1, version: 0 },
];

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let reply: { status: number; body: unknown } | null;
let urls: string[];
let listStatus: number;

beforeEach(() => {
  sent = [];
  urls = [];
  reply = null;
  listStatus = 200;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
      const method = init.method ?? 'GET';
      if (method !== 'GET') {
        sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
        return reply ? json(reply.body, reply.status) : method === 'DELETE' ? json(null, 204) : json(NEW);
      }
      urls.push(path);
      if (path === '/api/users/u1') return json(ANN);
      if (path === '/api/roles') return json(ROLES);
      if (listStatus !== 200) return json({ detail: 'You don\'t have permission to do that' }, listStatus);
      return json({ content: [ANN, NEW], page: 0, size: 25, totalElements: 2, totalPages: 1 });
    }),
  );
  render(
    <MemoryRouter initialEntries={['/admin/users']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/admin/users" element={<UsersPage />} />
      </Routes>
    </MemoryRouter>,
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Users', () => {
  it('lists users and filters by status', async () => {
    const user = userEvent.setup();
    const rows = within(await screen.findByRole('table', { name: 'Users' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('ANNAnn LeeActivelocked outOperatorDevicessigned in now');
    expect(rows[2]).toHaveTextContent('BOBBob RayActiveNoneDevices, DesktopNever');
    await user.selectOptions(screen.getByLabelText('Status'), 'Inactive');
    await vi.waitFor(() => expect(urls[urls.length - 1]).toContain('status=INACTIVE'));
  });

  it('creates a user with a PIN', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'New user' }));
    await user.click(screen.getByRole('button', { name: 'Create user' }));
    expect(screen.getByText('Enter a user ID')).toBeInTheDocument();
    expect(screen.getByText('Give a PIN, a password or both')).toBeInTheDocument();
    await user.type(screen.getByLabelText('User ID'), 'bob');
    await user.type(screen.getByLabelText('Name'), 'Bob Ray');
    await user.click(await screen.findByLabelText(/Operator/));
    const pin = screen.getByLabelText('PIN');
    expect(pin).toHaveAttribute('type', 'password');
    await user.type(pin, '1234');
    await user.click(screen.getByRole('button', { name: 'Create user' }));
    expect(screen.getByText(/Too easy to guess/)).toBeInTheDocument();
    expect(sent).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Make a PIN' }));
    expect(pin).toHaveAttribute('type', 'text');
    const made = (pin as HTMLInputElement).value;
    expect(made).toMatch(/^[0-9]{6}$/);
    await user.type(screen.getByLabelText('Password'), 'short');
    await user.click(screen.getByRole('button', { name: 'Create user' }));
    expect(screen.getByText('Use at least 10 characters')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Make a password' }));
    const password = (screen.getByLabelText('Password') as HTMLInputElement).value;
    expect(password).toMatch(/^[a-z2-9]{4}(-[a-z2-9]{4}){3}$/);
    reply = { status: 409, body: { detail: 'A user with this ID already exists', errors: { userCode: 'already used by another user' } } };
    await user.click(screen.getByRole('button', { name: 'Create user' }));
    expect(await screen.findByText('Already used by another user')).toBeInTheDocument();
    reply = null;
    await user.click(screen.getByRole('button', { name: 'Create user' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Created user BOB');
    expect(sent[1].body).toEqual({ userCode: 'BOB', displayName: 'Bob Ray', status: 'ACTIVE', roleIds: ['op'], pin: made, password });
  });

  it('shows what they are doing, and makes them inactive', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'ANN' }));
    expect(await screen.findByText(/In the middle of: Picking PT000009/)).toBeInTheDocument();
    const drawer = within(screen.getByRole('dialog'));
    await user.selectOptions(drawer.getByLabelText('Status'), 'Inactive');
    expect(screen.getByText(/Someone else can take it over/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByRole('status');
    expect(sent[0]).toEqual({
      method: 'PUT',
      path: '/api/users/u1',
      body: { userCode: 'ANN', displayName: 'Ann Lee', status: 'INACTIVE', roleIds: ['op'], version: 3 },
    });
  });

  it('unlocks, signs out and sets a new PIN', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'ANN' }));
    reply = { status: 200, body: { ...ANN, lockedUntil: null } };
    await user.click(await screen.findByRole('button', { name: 'Unlock' }));
    expect(await screen.findByRole('status')).toHaveTextContent('ANN can sign in again.');
    reply = { status: 200, body: { ...ANN, lockedUntil: null, devices: 0 } };
    await user.click(screen.getByRole('button', { name: 'Sign out everywhere' }));
    expect(await screen.findByText('ANN is signed out everywhere.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign out everywhere' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Set new PIN' })).toBeDisabled();
    await user.type(screen.getByLabelText('PIN'), '8264');
    await user.click(screen.getByRole('button', { name: 'Set new PIN' }));
    expect(await screen.findByText(/The new PIN works now/)).toBeInTheDocument();
    expect(screen.getByLabelText('PIN')).toHaveValue('');
    expect(sent.map((s) => `${s.method} ${s.path}`)).toEqual(['POST /api/users/u1/unlock', 'POST /api/users/u1/sign-out', 'PUT /api/users/u1/pin']);
    expect(sent[2].body).toEqual({ pin: '8264' });
  });

  it('says why someone cannot be deleted', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'ANN' }));
    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    reply = { status: 409, body: { detail: "ANN has worked in the warehouse and can't be deleted. Mark them Inactive instead." } };
    await user.click(within(screen.getByRole('group', { name: 'Confirm delete' })).getByRole('button', { name: 'Delete' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Mark them Inactive instead');
  });

  it('gives a password and changes roles', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'ANN' }));
    expect(await screen.findByText(/no password, so they can’t use the desktop/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Password'), 'a few words more');
    reply = { status: 200, body: { ...ANN, hasPassword: true } };
    await user.click(screen.getByRole('button', { name: 'Set new password' }));
    expect(await screen.findByText(/to choose their own with/)).toBeInTheDocument();
    expect(sent[0]).toEqual({ method: 'PUT', path: '/api/users/u1/password', body: { password: 'a few words more' } });
    reply = null;
    await user.click(screen.getByLabelText(/Operator/));
    await user.click(screen.getByLabelText(/Administrator/));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await vi.waitFor(() => expect(sent[1].body).toMatchObject({ roleIds: ['adm'] }));
  });

  it('shows why the list cannot be loaded', async () => {
    const user = userEvent.setup();
    await screen.findByRole('table', { name: 'Users' });
    listStatus = 403;
    await user.selectOptions(screen.getByLabelText('Status'), 'Active');
    expect(await screen.findByRole('alert')).toHaveTextContent("You don't have permission");
  });
});

describe('PINs', () => {
  it('are checked like the server does', () => {
    for (const weak of ['', '123', '12a4', '1111', '1234', '9876', '1234567890123']) expect(pinError(weak)).not.toBeNull();
    for (const fine of ['8264', '1357', '1122', '730519']) expect(pinError(fine)).toBeNull();
    expect(pinError(randomPin())).toBeNull();
  });
});
