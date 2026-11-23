import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { SessionView } from './api/session';
import type { ModuleDefinition } from './api/navigation';

const MODULES: ModuleDefinition[] = [
  {
    id: 'inbound',
    name: 'Inbound',
    description: 'Expected receipts and receiving',
    icon: 'inbound',
    pages: [
      { id: 'asns', title: 'ASNs', description: 'Advance shipping notices', route: '/inbound/asns', status: 'PLANNED' },
      { id: 'receiving', title: 'Receiving', description: 'Receive goods', route: '/inbound/receiving', status: 'AVAILABLE' },
    ],
  },
  {
    id: 'inventory',
    name: 'Inventory',
    description: 'Stock on hand',
    icon: 'inventory',
    pages: [
      { id: 'warehouse-map', title: 'Warehouse Map', description: 'Floor plan', route: '/inventory/map', status: 'PLANNED' },
    ],
  },
];

const SIGNED_IN: SessionView = {
  state: 'SIGNED_IN',
  user: { id: 'u1', userCode: 'BOSS', displayName: 'The Boss', permissions: ['DESKTOP'], mustChangePassword: false },
  setupHere: false,
};

/** Someone is signed in, and everything else gets this answer. */
function answer(body: string, status = 200) {
  return vi.fn(async (path: string) =>
    path === '/api/session' ? new Response(JSON.stringify(SIGNED_IN), { status: 200 }) : new Response(body, { status }),
  );
}

async function renderApp() {
  render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <App />
    </MemoryRouter>,
  );
  await screen.findByRole('navigation', { name: 'Page directory' });
}

describe('Home screen', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      answer(JSON.stringify(MODULES)),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('lists every page grouped by module', async () => {
    await renderApp();
    const main = screen.getByRole('main');

    const inbound = await within(main).findByRole('region', { name: 'Inbound' });
    expect(within(inbound).getByText('ASNs')).toBeInTheDocument();
    expect(within(inbound).getByText('Receiving')).toBeInTheDocument();
    expect(within(main).getByRole('region', { name: 'Inventory' })).toBeInTheDocument();
  });

  it('links available pages and marks planned pages as coming soon', async () => {
    await renderApp();
    const main = screen.getByRole('main');

    const receiving = await within(main).findByRole('link', { name: /Receiving/ });
    expect(receiving).toHaveAttribute('href', '/inbound/receiving');
    expect(within(main).queryByRole('link', { name: /ASNs/ })).not.toBeInTheDocument();
    expect(within(main).getAllByText('Coming soon')).toHaveLength(2);
  });

  it('shows page counts', async () => {
    await renderApp();
    const summary = await screen.findByText('Areas');
    const dl = summary.closest('dl')!;
    expect(within(dl).getByText('2', { selector: 'dd' })).toBeInTheDocument();
    expect(within(dl).getByText('3', { selector: 'dd' })).toBeInTheDocument();
    expect(within(dl).getByText('1', { selector: 'dd' })).toBeInTheDocument();
  });

  it('filters pages by search', async () => {
    const user = userEvent.setup();
    await renderApp();
    const main = screen.getByRole('main');

    await user.type(await within(main).findByRole('searchbox', { name: 'Search pages' }), 'map');

    expect(within(main).getByText('Warehouse Map')).toBeInTheDocument();
    expect(within(main).queryByRole('region', { name: 'Inbound' })).not.toBeInTheDocument();
  });

  it('shows an error with retry when the directory cannot load', async () => {
    vi.stubGlobal('fetch', answer('', 500));
    await renderApp();

    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 500');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

describe('Sidebar directory', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', answer(JSON.stringify(MODULES)));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('expands a module to show its pages', async () => {
    const user = userEvent.setup();
    await renderApp();
    const nav = screen.getByRole('navigation', { name: 'Page directory' });

    const inbound = await within(nav).findByRole('button', { name: 'Inbound' });
    expect(inbound).toHaveAttribute('aria-expanded', 'false');
    expect(within(nav).queryByText('ASNs')).not.toBeInTheDocument();

    await user.click(inbound);

    expect(inbound).toHaveAttribute('aria-expanded', 'true');
    expect(within(nav).getByText('ASNs')).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Receiving' })).toHaveAttribute('href', '/inbound/receiving');
  });

  it('has a Home link', async () => {
    await renderApp();
    const nav = screen.getByRole('navigation', { name: 'Page directory' });
    await within(nav).findByRole('button', { name: 'Inbound' });
    expect(within(nav).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
  });
});
