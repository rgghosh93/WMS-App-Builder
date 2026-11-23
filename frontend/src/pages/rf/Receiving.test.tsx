import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AsnDetail, AsnItem, AsnLine, AsnReceipt, LpnCheck } from '../../api/asns';
import { RfApp } from './RfApp';

const USER = { id: 'u1', userCode: 'ANN', displayName: 'Ann Lee' };
const LOCATION = { id: 'l1', locationId: 'DC1-RCV-01', type: 'Receiving' };
const DOOR = { id: 'l2', locationId: 'DC1-DOCK-01', type: 'Dock door' };
const BARS: AsnItem = {
  id: 'i1',
  itemId: 'SKU-BAR',
  description: 'Protein bar',
  lotControlled: true,
  serialControlled: false,
  expiryControlled: true,
  shelfLifeDays: null,
  scans: [
    { value: '111000', uom: 'EA', eachesPerUnit: 1 },
    { value: '111006', uom: 'CS', eachesPerUnit: 6 },
  ],
};
const DRILL: AsnItem = {
  ...BARS,
  id: 'i2',
  itemId: 'SKU-DRILL',
  description: 'Drill',
  lotControlled: false,
  serialControlled: true,
  expiryControlled: false,
  scans: [{ value: '222000', uom: 'EA', eachesPerUnit: 1 }],
};
const line = (over: Partial<AsnLine>): AsnLine => ({
  id: 'x1',
  lineNumber: 1,
  item: BARS,
  expectedQuantity: 60,
  receivedQuantity: 0,
  remaining: 60,
  lotNumber: 'L1',
  expiryDate: '2027-06-01',
  lpnId: null,
  ...over,
});
const ASN: AsnDetail = {
  id: 'a1',
  asnNumber: 'AS000001',
  vendorName: 'Sunrise Foods',
  reference: null,
  expectedDate: '2026-11-21',
  status: 'EXPECTED',
  expectedQuantity: 62,
  receivedQuantity: 0,
  lines: [line({}), line({ id: 'x2', lineNumber: 2, item: DRILL, expectedQuantity: 2, remaining: 2, lotNumber: null, expiryDate: null })],
  receipts: [],
  events: [],
  closedBy: null,
  createdAt: '2026-11-20T10:00:00Z',
  closedAt: null,
};
const RECEIPT: AsnReceipt = {
  id: 'r1',
  lineNumber: 1,
  itemId: 'SKU-BAR',
  description: 'Protein bar',
  quantity: 12,
  lotNumber: 'L1',
  serialNumber: null,
  expiryDate: '2027-06-01',
  condition: 'AVAILABLE',
  lpn: { id: 'c1', lpnId: 'LP1', containerType: 'PLT' },
  location: LOCATION,
  receivedBy: 'Ann Lee',
  receivedAt: '2026-11-21T10:00:00Z',
  reversedBy: null,
  reversedAt: null,
};
const RECEIVED: AsnDetail = { ...ASN, status: 'RECEIVING', receivedQuantity: 12, receipts: [RECEIPT] };
const NEW_LPN: LpnCheck = {
  lpnId: 'LP1',
  kind: 'NEW',
  needsType: true,
  containerType: null,
  expected: [],
  whyNotAll: null,
  types: [
    { id: 'plt', containerTypeId: 'PLT', description: 'Pallet' },
    { id: 'ctn', containerTypeId: 'CTN', description: 'Carton' },
  ],
};
const OPEN_LPN: LpnCheck = { ...NEW_LPN, kind: 'OPEN', needsType: false, types: [] };

type Reply = { status?: number; body: unknown } | 'offline';
let posts: { path: string; body: Record<string, unknown> }[];
let replies: Record<string, Reply[]>;
let asn: AsnDetail;

beforeEach(() => {
  localStorage.clear();
  posts = [];
  replies = {};
  asn = ASN;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      if ((init.method ?? 'GET') === 'GET') {
        if (path === '/api/rf/session') return json(USER);
        if (path === '/api/rf/receiving/locations') return json([LOCATION, DOOR]);
        if (path === '/api/rf/receiving/locations/DC1-RCV-01') return json(LOCATION);
        if (path === '/api/rf/receiving/locations/DC1-DOCK-01') return json(DOOR);
        if (path === '/api/rf/receiving/asns') return json([{ ...asn, lineCount: 2 }]);
        if (path === '/api/rf/receiving/asns/AS000001') return json(asn);
        if (path === '/api/rf/receiving/blind/a1/items/222000') return json(DRILL);
        if (path.startsWith('/api/rf/receiving/blind/')) return json({ detail: 'NOPE is not a known item' }, 400);
        return json({ detail: 'NOPE is not a known ASN' }, 400);
      }
      posts.push({ path, body: JSON.parse(String(init.body)) });
      const reply = replies[path]?.shift();
      if (reply === 'offline') throw new TypeError('Failed to fetch');
      return reply ? json(reply.body, reply.status) : json({ detail: 'Not found' }, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const API = '/api/rf/receiving/asns/a1';

/** Signs in, chooses the location and the ASN, and scans an LPN. */
async function openLpn(user: UserEvent, check: LpnCheck | null, at = 'DC1-RCV-01') {
  render(
    <MemoryRouter initialEntries={['/rf/receiving']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/rf/*" element={<RfApp />} />
      </Routes>
    </MemoryRouter>,
  );
  await user.click(await screen.findByRole('button', { name: new RegExp(at) }));
  await user.click(await screen.findByRole('button', { name: /AS000001/ }));
  await screen.findByText('Scan an LPN to receive into');
  if (check) {
    replies[`${API}/lpn`] = [{ body: check }];
    await scan(user, 'LPN', check.lpnId);
  }
}

async function scan(user: UserEvent, label: RegExp | string, value: string) {
  await user.type(await screen.findByLabelText(label), `${value}{Enter}`);
}

async function enterBars(user: UserEvent, quantity: string) {
  await scan(user, 'Item', '111006');
  await user.click(await screen.findByRole('button', { name: 'Lot L1' }));
  await user.click(await screen.findByRole('button', { name: 'Use this date' }));
  await scan(user, /Quantity \(CS of 6 EA\), 10 expected/, quantity);
}

describe('Receiving', () => {
  it('receives cases into a new LPN, with lot and expiry', async () => {
    const user = userEvent.setup();
    await openLpn(user, NEW_LPN);
    await user.click(await screen.findByRole('button', { name: /PLT/ }));
    replies[`${API}/receive`] = [{ body: RECEIVED }];
    await enterBars(user, '2');
    expect(await screen.findByRole('status')).toHaveTextContent('Received 2 CS of SKU-BAR into LP1');
    const sent = posts[posts.length - 1].body;
    expect(sent).toMatchObject({
      locationId: 'DC1-RCV-01',
      lpnId: 'LP1',
      containerTypeId: 'plt',
      itemBarcode: '111006',
      quantity: 2,
      lotNumber: 'L1',
      expiryDate: '2027-06-01',
      condition: 'AVAILABLE',
      allowOver: false,
    });
    expect(sent.requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    // Ready for the next item, and the type is remembered for the next LPN.
    expect(await screen.findByLabelText('Item')).toHaveFocus();
    replies[`${API}/lpn`] = [{ body: { ...NEW_LPN, lpnId: 'LP2' } }];
    await user.click(screen.getByRole('button', { name: 'Next LPN' }));
    await scan(user, 'LPN', 'LP2');
    expect(await screen.findByText('· used last')).toBeInTheDocument();
  });

  it('receives at a dock door', async () => {
    const user = userEvent.setup();
    await openLpn(user, null, 'DC1-DOCK-01');
    replies[`${API}/lpn`] = [{ body: NEW_LPN }];
    await scan(user, 'LPN', 'LP1');
    await screen.findByText('What is it?');
    expect(posts[0].body).toEqual({ locationId: 'DC1-DOCK-01', lpnId: 'LP1' });
  });

  it('makes a new LPN number', async () => {
    const user = userEvent.setup();
    await openLpn(user, null);
    replies[`${API}/lpn`] = [{ body: { ...NEW_LPN, lpnId: 'LP00000001' } }];
    await user.click(screen.getByRole('button', { name: 'Make a new LPN number' }));
    expect(await screen.findByText('LP00000001', { selector: '.rf-card__big' })).toBeInTheDocument();
    expect(posts[0].body).toEqual({ locationId: 'DC1-RCV-01', lpnId: '' });
  });

  it('scans a serial number for each unit', async () => {
    const user = userEvent.setup();
    await openLpn(user, OPEN_LPN);
    await scan(user, 'Item', '222000');
    await scan(user, 'Serial number 1', 'd1');
    await scan(user, 'Serial number 2', 'D1');
    expect(await screen.findByRole('alert')).toHaveTextContent('D1 is already scanned');
    await scan(user, 'Serial number 2', '222000');
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('That is the item'));
    await scan(user, 'Serial number 2', 'd2');
    await user.selectOptions(screen.getByLabelText('Condition'), 'Damaged');
    replies[`${API}/receive`] = [{ body: RECEIVED }];
    await user.click(await screen.findByRole('button', { name: 'Receive 2' }));
    await screen.findByRole('status');
    expect(posts[posts.length - 1].body).toMatchObject({ quantity: 2, serialNumbers: ['D1', 'D2'], condition: 'DAMAGED' });
  });

  it('refuses an item that is not on the ASN', async () => {
    const user = userEvent.setup();
    await openLpn(user, OPEN_LPN);
    await scan(user, 'Item', '999');
    expect(await screen.findByRole('alert')).toHaveTextContent('999 is not on AS000001');
    expect(posts).toHaveLength(1);
  });

  it('confirms receiving more than expected', async () => {
    const user = userEvent.setup();
    await openLpn(user, OPEN_LPN);
    const over: Reply = { status: 409, body: { detail: 'That is 6 EA more of SKU-BAR than AS000001 says (60 EA left).', errors: { over: '6' } } };
    replies[`${API}/receive`] = [over, { body: RECEIVED }];
    await enterBars(user, '11');
    await user.click(await screen.findByRole('button', { name: 'Change it' }));
    expect(screen.getByLabelText(/Quantity/)).toHaveFocus();
    replies[`${API}/receive`].unshift(over);
    await scan(user, /Quantity/, '11');
    const card = await screen.findByRole('group', { name: 'More than expected' });
    await user.click(within(card).getByRole('button', { name: 'Receive it anyway' }));
    await screen.findByRole('status');
    expect(posts[posts.length - 1].body).toMatchObject({ quantity: 11, allowOver: true });
  });

  it('keeps a receipt that got no answer and sends it again under the same ID', async () => {
    const user = userEvent.setup();
    await openLpn(user, OPEN_LPN);
    replies[`${API}/receive`] = ['offline', { body: RECEIVED }];
    await enterBars(user, '2');
    await user.click(await screen.findByRole('button', { name: 'Send again' }));
    await screen.findByRole('status');
    const [first, second] = posts.filter((p) => p.path.endsWith('/receive')).map((p) => p.body.requestId);
    expect(second).toBe(first);
  });

  it("receives the vendor's LPN whole", async () => {
    const user = userEvent.setup();
    await openLpn(user, { ...NEW_LPN, kind: 'ANNOUNCED', expected: [line({ lpnId: 'LP1' })] });
    await user.click(await screen.findByRole('button', { name: /PLT/ }));
    expect(await screen.findByLabelText('Announced contents')).toHaveTextContent('lot L1');
    replies[`${API}/receive-lpn`] = [{ body: RECEIVED }];
    await user.click(screen.getByRole('button', { name: 'Receive all 60 EA' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Received LP1: 60 EA');
    expect(await screen.findByLabelText('LPN')).toHaveFocus();
  });

  it("says why the vendor's LPN is received item by item", async () => {
    const user = userEvent.setup();
    await openLpn(user, { ...OPEN_LPN, kind: 'ANNOUNCED', expected: [line({ lpnId: 'LP1' })], whyNotAll: 'SKU-BAR needs its lot' });
    expect(await screen.findByText(/SKU-BAR needs its lot, so this LPN/)).toBeInTheDocument();
  });

  it('undoes a receipt', async () => {
    const user = userEvent.setup();
    asn = RECEIVED;
    await openLpn(user, null);
    await user.click(screen.getByRole('button', { name: 'Undo a receipt' }));
    await user.click(await screen.findByRole('button', { name: /SKU-BAR 12 EA/ }));
    replies['/api/rf/receiving/receipts/r1/reverse'] = [{ body: { ...ASN, receipts: [{ ...RECEIPT, reversedAt: '2026-11-21T11:00:00Z' }] } }];
    await user.click(await screen.findByRole('button', { name: 'Undo it' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Undone: 12 EA of SKU-BAR out of LP1.');
    expect(screen.getByText('Nothing left to undo.')).toBeInTheDocument();
  });

  it('confirms closing short', async () => {
    const user = userEvent.setup();
    asn = RECEIVED;
    await openLpn(user, null);
    await user.click(screen.getByRole('button', { name: 'Close ASN' }));
    replies[`${API}/close`] = [
      { status: 409, body: { detail: '50 EA of AS000001 have not been received.', errors: { short: '50' } } },
      { body: { ...RECEIVED, status: 'RECEIVED' } },
    ];
    await user.click(await screen.findByRole('button', { name: 'Close ASN' }));
    await user.click(await screen.findByRole('button', { name: 'Close short' }));
    expect(await screen.findByRole('status')).toHaveTextContent('AS000001 is closed.');
    expect(posts.map((p) => p.body.acceptShort)).toEqual([false, true]);
  });
});

describe('Receiving without an ASN', () => {
  const BLIND: AsnDetail = { ...ASN, asnNumber: 'BR000001', vendorName: 'Sunrise Foods', status: 'RECEIVING', blind: true, expectedQuantity: 0, expectedDate: null, lines: [] };

  async function begin(user: UserEvent) {
    render(
      <MemoryRouter initialEntries={['/rf/receiving']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/rf/*" element={<RfApp />} />
        </Routes>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole('button', { name: /DC1-RCV-01/ }));
    await user.click(await screen.findByRole('button', { name: 'Receive without an ASN' }));
  }

  it('starts one, receives any item, and finishes it', async () => {
    const user = userEvent.setup();
    await begin(user);
    await user.type(screen.getByLabelText('Who is it from? (optional)'), 'Sunrise Foods');
    await user.type(screen.getByLabelText(/PO, bill of lading/), 'PO-9');
    replies['/api/rf/receiving/blind'] = ['offline', { body: BLIND }];
    await user.click(screen.getByRole('button', { name: 'Start receiving' }));
    expect(await screen.findByRole('alert')).toHaveTextContent("Can't reach the WMS");
    await user.click(screen.getByRole('button', { name: 'Start receiving' }));
    expect(await screen.findByText('0 EA received, without an ASN')).toBeInTheDocument();
    expect(posts[1].body).toEqual({ requestId: posts[0].body.requestId, owner: null, vendorName: 'Sunrise Foods', reference: 'PO-9' });
    expect(screen.queryByRole('button', { name: 'Finish receipt' })).not.toBeInTheDocument();

    replies[`${API}/lpn`] = [{ body: OPEN_LPN }];
    await scan(user, 'LPN', 'LP1');
    await scan(user, 'Item', 'nope');
    expect(await screen.findByRole('alert')).toHaveTextContent('NOPE is not a known item');
    await scan(user, 'Item', '222000');
    await scan(user, 'Serial number 1', 'd-1');
    replies[`${API}/receive`] = [
      { body: { ...BLIND, receivedQuantity: 1, lines: [line({ item: DRILL, expectedQuantity: 0, receivedQuantity: 1, remaining: 0, lotNumber: null })], receipts: [{ ...RECEIPT, quantity: 1 }] } },
    ];
    await user.click(screen.getByRole('button', { name: 'Receive 1' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Received 1 EA of SKU-DRILL into LP1');
    await user.click(screen.getByRole('button', { name: 'Next LPN' }));
    expect(screen.getByText('1 EA received, without an ASN')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Finish receipt' }));
    replies[`${API}/close`] = [{ body: { ...BLIND, status: 'RECEIVED' } }];
    await user.click(within(screen.getByRole('group', { name: 'Finish receipt' })).getByRole('button', { name: 'Finish receipt' }));
    expect(await screen.findByRole('status')).toHaveTextContent('BR000001 is closed.');
  });

  it('asks whose it is, and can be discarded', async () => {
    const user = userEvent.setup();
    await begin(user);
    replies['/api/rf/receiving/blind'] = [{ status: 409, body: { detail: 'Say whose it is.', errors: { owner: 'ACME,HOUSE' } } }, { body: BLIND }];
    await user.click(screen.getByRole('button', { name: 'Start receiving' }));
    await user.click(await screen.findByRole('button', { name: 'ACME' }));
    expect(posts[1].body).toMatchObject({ owner: 'ACME', requestId: posts[0].body.requestId });
    replies['/api/rf/receiving/blind/a1/discard'] = [{ body: { ...BLIND, status: 'CANCELLED' } }];
    await user.click(await screen.findByRole('button', { name: 'Discard it: nothing was received' }));
    expect(await screen.findByText('Scan an ASN')).toBeInTheDocument();
  });
});
