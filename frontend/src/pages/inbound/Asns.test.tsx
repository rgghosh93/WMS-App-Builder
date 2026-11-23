import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AsnDetail } from '../../api/asns';
import { AsnDetailPage } from './AsnDetailPage';
import { AsnsPage } from './AsnsPage';
import { LpnLabelsPage } from './LpnLabelsPage';

const ITEM = {
  id: 'i1',
  itemId: 'SKU-BAR',
  description: 'Protein bar',
  lotControlled: true,
  serialControlled: false,
  expiryControlled: true,
  shelfLifeDays: null,
  scans: [],
};
const ASN: AsnDetail = {
  id: 'a1',
  asnNumber: 'AS000001',
  vendorName: 'Sunrise Foods',
  reference: 'PO-1',
  expectedDate: '2026-11-21',
  status: 'RECEIVED',
  expectedQuantity: 70,
  receivedQuantity: 57,
  lines: [
    { id: 'x1', lineNumber: 1, item: ITEM, expectedQuantity: 60, receivedQuantity: 45, remaining: 15, lotNumber: 'L1', expiryDate: '2027-06-01', lpnId: 'V-1' },
    { id: 'x2', lineNumber: 2, item: { ...ITEM, itemId: 'SKU-CUP' }, expectedQuantity: 10, receivedQuantity: 12, remaining: 0, lotNumber: null, expiryDate: null, lpnId: null },
  ],
  receipts: [
    {
      id: 'r1', lineNumber: 1, itemId: 'SKU-BAR', description: 'Protein bar', quantity: 45, lotNumber: 'L1', serialNumber: null, expiryDate: '2027-06-01',
      condition: 'ON_HOLD', lpn: { id: 'c1', lpnId: 'V-1', containerType: 'PLT' }, location: { id: 'l1', locationId: 'DC1-RCV-01' },
      receivedBy: 'Ann Lee', receivedAt: '2026-11-21T10:00:00Z', reversedBy: null, reversedAt: null,
    },
    {
      id: 'r2', lineNumber: 2, itemId: 'SKU-CUP', description: 'Cup', quantity: 99, lotNumber: null, serialNumber: null, expiryDate: null,
      condition: 'AVAILABLE', lpn: { id: 'c2', lpnId: 'LP-WRONG', containerType: 'PLT' }, location: { id: 'l1', locationId: 'DC1-RCV-01' },
      receivedBy: 'Ann Lee', receivedAt: '2026-11-21T10:00:00Z', reversedBy: 'Bob Ray', reversedAt: '2026-11-21T10:05:00Z',
    },
  ],
  events: [{ occurredAt: '2026-11-21T09:00:00Z', type: 'CREATED', message: 'ASN from Sunrise Foods' }],
  closedBy: 'Ann Lee',
  createdAt: '2026-11-20T10:00:00Z',
  closedAt: '2026-11-21T12:00:00Z',
};

let asn: AsnDetail;
let posts: { path: string; body: unknown }[];
let reply: { status: number; body: unknown } | null;
let urls: string[];

beforeEach(() => {
  asn = ASN;
  posts = [];
  urls = [];
  reply = null;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      if (init.method === 'POST') {
        posts.push({ path, body: init.body ? JSON.parse(String(init.body)) : null });
        return reply ? json(reply.body, reply.status) : json(asn, 201);
      }
      urls.push(path);
      if (path.startsWith('/api/items')) return json({ content: [{ ...ITEM, status: 'ACTIVE' }], page: 0, size: 8, totalElements: 1, totalPages: 1 });
      if (path.startsWith('/api/asns?')) return json({ content: [{ ...asn, lineCount: 2 }], page: 0, size: 25, totalElements: 1, totalPages: 1 });
      return json(asn);
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
        <Route path="/inbound/asns" element={<AsnsPage />} />
        <Route path="/inbound/asns/:id" element={<AsnDetailPage />} />
        <Route path="/print/asns/:id/lpn-labels" element={<LpnLabelsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ASNs', () => {
  it('lists ASNs and filters by status', async () => {
    const user = userEvent.setup();
    renderAt('/inbound/asns');
    const row = (await screen.findByRole('link', { name: 'AS000001' })).closest('tr')!;
    expect(row).toHaveTextContent('Sunrise Foods');
    expect(row).toHaveTextContent('57 / 70 EA');
    await user.selectOptions(screen.getByLabelText('Status'), 'Receiving');
    await vi.waitFor(() => expect(urls[urls.length - 1]).toContain('status=RECEIVING'));
  });

  it('shows what was expected, received, short and over', async () => {
    renderAt('/inbound/asns/a1');
    const lines = within(await screen.findByRole('table', { name: 'Lines' })).getAllByRole('row');
    expect(lines[1]).toHaveTextContent('15 short');
    expect(lines[1]).toHaveTextContent('V-1');
    expect(lines[2]).toHaveTextContent('2 over');
    const receipts = within(screen.getByRole('table', { name: 'Receipts' })).getAllByRole('row');
    expect(receipts[1]).toHaveTextContent('On hold');
    expect(receipts[2]).toHaveTextContent('reversed by Bob Ray');
    expect(screen.queryByRole('button', { name: 'Cancel ASN' })).not.toBeInTheDocument();
  });

  it('cancels an ASN nothing was received on', async () => {
    const user = userEvent.setup();
    asn = { ...ASN, status: 'EXPECTED', receivedQuantity: 0, receipts: [], closedAt: null, closedBy: null };
    renderAt('/inbound/asns/a1');
    await user.click(await screen.findByRole('button', { name: 'Cancel ASN' }));
    reply = { status: 200, body: { ...asn, status: 'CANCELLED' } };
    await user.click(within(screen.getByRole('group', { name: 'Confirm cancel' })).getByRole('button', { name: 'Cancel ASN' }));
    expect(await screen.findByText('Cancelled', { selector: '.badge' })).toBeInTheDocument();
    expect(posts[0].path).toBe('/api/asns/a1/cancel');
  });

  it('creates an ASN', async () => {
    const user = userEvent.setup();
    renderAt('/inbound/asns');
    await user.click(await screen.findByRole('button', { name: 'New ASN' }));
    await user.click(screen.getByRole('button', { name: 'Create ASN' }));
    expect(screen.getByText('Enter the vendor')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Vendor'), 'Sunrise Foods');
    await user.type(screen.getByLabelText('Item'), 'bar');
    await user.click(await screen.findByRole('option', { name: /SKU-BAR/ }));
    await user.type(screen.getByLabelText('Quantity (EA)'), '60');
    await user.type(screen.getByLabelText("Vendor's LPN"), 'v-1');
    await user.type(screen.getByLabelText('Lot'), 'l1');
    await user.type(screen.getByLabelText('Expiry date'), '2027-06-01');
    reply = { status: 400, body: { detail: 'Some lines need attention', errors: { 'lines[0].lpnId': 'V-1 is already in the warehouse' } } };
    await user.click(screen.getByRole('button', { name: 'Create ASN' }));
    expect(await screen.findByText('V-1 is already in the warehouse')).toBeInTheDocument();
    reply = null;
    await user.click(screen.getByRole('button', { name: 'Create ASN' }));
    expect(await screen.findByRole('heading', { name: /AS000001/ })).toBeInTheDocument();
    expect(posts[1].body).toEqual({
      asnNumber: null,
      vendorName: 'Sunrise Foods',
      reference: null,
      expectedDate: null,
      lines: [{ itemId: 'SKU-BAR', quantity: 60, lotNumber: 'L1', expiryDate: '2027-06-01', lpnId: 'V-1' }],
    });
  });

  it('prints a label for each LPN received', async () => {
    renderAt('/print/asns/a1/lpn-labels');
    const label = await screen.findByRole('article', { name: 'Label V-1' });
    expect(label).toHaveTextContent('45 EA · lot L1');
    expect(screen.getAllByRole('article')).toHaveLength(1);
  });
});
