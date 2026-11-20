import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { UomsPage } from './UomsPage';
import type { Uom } from '../../api/uoms';

const EA: Uom = {
  id: 'u-ea',
  uomCode: 'EA',
  description: 'Each',
  kind: 'EACH',
  status: 'ACTIVE',
  base: true,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-20T10:00:00Z',
  version: 0,
};
const CS: Uom = { ...EA, id: 'u-cs', uomCode: 'CS', description: 'Case', kind: 'CASE', base: false, version: 1 };

let calls: { method: string; url: URL; body?: Record<string, unknown> }[];
let deleteResponse: Response | undefined;

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  calls = [];
  deleteResponse = undefined;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit = {}) => {
      const url = new URL(input, 'http://localhost');
      const method = init.method ?? 'GET';
      const body = init.body ? JSON.parse(String(init.body)) : undefined;
      calls.push({ method, url, body });
      if (method === 'GET') return json([EA, CS]);
      if (method === 'POST') return json({ ...CS, ...body, id: 'new' }, 201);
      if (method === 'PUT') return json({ ...CS, ...body });
      if (method === 'DELETE') return deleteResponse ?? new Response(null, { status: 204 });
      return json({}, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/master-data/units-of-measure']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/master-data/units-of-measure" element={<UomsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Units of Measure page', () => {
  it('lists units with the base unit marked', async () => {
    renderPage();
    const ea = (await screen.findByRole('button', { name: 'EA' })).closest('tr')!;
    expect(within(ea).getByText('Base unit')).toBeInTheDocument();
    // Description and packaging level are both "Each".
    expect(within(ea).getAllByText('Each')).toHaveLength(2);
    const cs = screen.getByRole('button', { name: 'CS' }).closest('tr')!;
    expect(within(cs).getAllByText('Case')).toHaveLength(2);
    expect(within(cs).queryByText('Base unit')).not.toBeInTheDocument();
  });

  it('creates a unit', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'New unit' }));
    const dialog = screen.getByRole('dialog', { name: 'New unit of measure' });

    await user.type(within(dialog).getByLabelText('Code'), 'pl');
    await user.type(within(dialog).getByLabelText('Description'), 'Pallet');
    await user.selectOptions(within(dialog).getByLabelText('Packaging level'), 'PALLET');
    expect(within(within(dialog).getByLabelText('Packaging level')).queryByRole('option', { name: 'Each' })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Create unit' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({
      uomCode: 'PL',
      description: 'Pallet',
      kind: 'PALLET',
      status: 'ACTIVE',
    });
  });

  it('only lets the base unit description change', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'EA' }));
    const dialog = screen.getByRole('dialog', { name: 'Edit EA' });

    expect(within(dialog).getByLabelText('Code')).toBeDisabled();
    expect(within(dialog).getByLabelText('Packaging level')).toBeDisabled();
    expect(within(dialog).getByLabelText('Status')).toBeDisabled();
    expect(within(dialog).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('shows why a unit in use cannot be deleted', async () => {
    deleteResponse = json({ detail: 'CS can\'t be deleted: 12 items use it. Mark it Inactive instead.' }, 409);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'CS' }));
    const dialog = screen.getByRole('dialog');

    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await user.click(within(within(dialog).getByRole('group', { name: 'Confirm delete' })).getByRole('button', { name: 'Delete' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('12 items use it');
  });
});
