import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { NavigationProvider } from './navigation/NavigationContext';
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

function renderApp() {
  return render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <NavigationProvider>
        <App />
      </NavigationProvider>
    </MemoryRouter>,
  );
}

describe('Home screen', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(JSON.stringify(MODULES), { status: 200 })),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('lists every page grouped by module', async () => {
    renderApp();
    const main = screen.getByRole('main');

    const inbound = await within(main).findByRole('region', { name: 'Inbound' });
    expect(within(inbound).getByText('ASNs')).toBeInTheDocument();
    expect(within(inbound).getByText('Receiving')).toBeInTheDocument();
    expect(within(main).getByRole('region', { name: 'Inventory' })).toBeInTheDocument();
  });

  it('links available pages and marks planned pages as coming soon', async () => {
    renderApp();
    const main = screen.getByRole('main');

    const receiving = await within(main).findByRole('link', { name: /Receiving/ });
    expect(receiving).toHaveAttribute('href', '/inbound/receiving');
    expect(within(main).queryByRole('link', { name: /ASNs/ })).not.toBeInTheDocument();
    expect(within(main).getAllByText('Coming soon')).toHaveLength(2);
  });

  it('shows page counts', async () => {
    renderApp();
    const summary = await screen.findByText('Areas');
    const dl = summary.closest('dl')!;
    expect(within(dl).getByText('2', { selector: 'dd' })).toBeInTheDocument();
    expect(within(dl).getByText('3', { selector: 'dd' })).toBeInTheDocument();
    expect(within(dl).getByText('1', { selector: 'dd' })).toBeInTheDocument();
  });

  it('filters pages by search', async () => {
    const user = userEvent.setup();
    renderApp();
    const main = screen.getByRole('main');

    await user.type(await within(main).findByRole('searchbox', { name: 'Search pages' }), 'map');

    expect(within(main).getByText('Warehouse Map')).toBeInTheDocument();
    expect(within(main).queryByRole('region', { name: 'Inbound' })).not.toBeInTheDocument();
  });

  it('shows an error with retry when the directory cannot load', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })));
    renderApp();

    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 500');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

describe('Sidebar directory', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(MODULES), { status: 200 })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('expands a module to show its pages', async () => {
    const user = userEvent.setup();
    renderApp();
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
    renderApp();
    const nav = screen.getByRole('navigation', { name: 'Page directory' });
    await within(nav).findByRole('button', { name: 'Inbound' });
    expect(within(nav).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/');
  });
});
