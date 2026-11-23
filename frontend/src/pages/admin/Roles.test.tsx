import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PermissionInfo, Role } from '../../api/users';
import { RolesPage } from './RolesPage';

const PERMISSIONS: PermissionInfo[] = [
  { id: 'DESKTOP', group: 'DESKTOP', label: 'Use the desktop', description: '' },
  { id: 'ORDERS', group: 'DESKTOP', label: 'Manage orders', description: '' },
  { id: 'DEVICE_PICK', group: 'DEVICE', label: 'Pick', description: '' },
  { id: 'DEVICE_PACK', group: 'DEVICE', label: 'Pack', description: '' },
];
const ROLES: Role[] = [
  { id: 'adm', name: 'Administrator', description: null, administrator: true, permissions: ['DESKTOP', 'ORDERS', 'DEVICE_PICK', 'DEVICE_PACK'], userCount: 1, version: 0 },
  { id: 'clk', name: 'Clerk', description: 'Orders', administrator: false, permissions: ['DESKTOP', 'ORDERS', 'DEVICE_PICK'], userCount: 2, version: 5 },
];

let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let reply: { status: number; body: unknown } | null;

beforeEach(() => {
  sent = [];
  reply = null;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
      const method = init.method ?? 'GET';
      if (method === 'GET') return json(path === '/api/roles' ? ROLES : PERMISSIONS);
      sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
      return reply ? json(reply.body, reply.status) : method === 'DELETE' ? json(null, 204) : json(ROLES[1]);
    }),
  );
  render(
    <MemoryRouter initialEntries={['/admin/roles']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/admin/roles" element={<RolesPage />} />
      </Routes>
    </MemoryRouter>,
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Roles', () => {
  it('lists what each role may do', async () => {
    const rows = within(await screen.findByRole('table', { name: 'Roles' })).getAllByRole('row');
    expect(rows[1]).toHaveTextContent('AdministratorEverythingEverything1');
    expect(rows[2]).toHaveTextContent('ClerkOrdersEverythingPick2');
  });

  it('creates a role, adding the desktop to what needs it', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'New role' }));
    await user.click(screen.getByRole('button', { name: 'Create role' }));
    expect(screen.getByText('Enter a name')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Name'), 'Desk');
    await user.click(screen.getByLabelText('Manage orders'));
    expect(screen.getByLabelText('Use the desktop')).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Create role' }));
    await screen.findByRole('status');
    expect(sent[0].body).toMatchObject({ name: 'Desk', description: null, permissions: ['DESKTOP', 'ORDERS'] });
  });

  it('takes the other desktop permissions away with the desktop', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Clerk' }));
    expect(screen.getByText(/2 users have this role/)).toBeInTheDocument();
    await user.click(screen.getByLabelText('Use the desktop'));
    expect(screen.getByLabelText('Manage orders')).not.toBeChecked();
    reply = { status: 409, body: { detail: 'Then nobody could manage users any more. Give someone else that first.' } };
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('nobody could manage users');
    expect(sent[0]).toMatchObject({ method: 'PUT', path: '/api/roles/clk', body: { permissions: ['DEVICE_PICK'], version: 5 } });
  });

  it('shows the administrator role without letting it be changed', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Administrator' }));
    expect(screen.getByLabelText('Manage orders')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('deletes a role', async () => {
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Clerk' }));
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(within(screen.getByRole('group', { name: 'Confirm delete' })).getByRole('button', { name: 'Delete' }));
    await vi.waitFor(() => expect(sent[0]).toMatchObject({ method: 'DELETE', path: '/api/roles/clk' }));
  });
});
