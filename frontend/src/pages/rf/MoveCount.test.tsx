import { render, screen, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ControlItem, CountState, MoveSource } from '../../api/control';
import { RfApp } from './RfApp';

const USER = { id: 'u1', userCode: 'ANN', displayName: 'Ann Lee' };
const BAR: ControlItem = { id: 'i1', itemId: 'SKU-BAR', description: 'Protein bar', lotControlled: true, serialControlled: false, expiryControlled: true };
const DRILL: ControlItem = { ...BAR, id: 'i2', itemId: 'SKU-DRILL', description: 'Drill', lotControlled: false, serialControlled: true, expiryControlled: false };
const SCANS = [
  { value: '111000', uom: 'EA', eachesPerUnit: 1 },
  { value: '111006', uom: 'CS', eachesPerUnit: 6 },
];
const LINE = { inventoryId: 's1', item: BAR, scans: SCANS, lotNumber: 'L1', serialNumber: null, expiryDate: null, status: 'AVAILABLE' as const, quantity: 60, free: 40 };
const PALLET: MoveSource = { lpn: 'P1', containerType: 'GMA pallet', location: 'DC1-RS-01', whole: true, byItem: false, whyStays: null, lines: [LINE], lpns: [] };
const SHELF: MoveSource = { lpn: null, containerType: null, location: 'DC1-BK-01', whole: false, byItem: false, whyStays: null, lines: [LINE], lpns: [] };
const NONE: CountState = { counting: null, waitingCount: 1, waiting: [{ number: 'CC000001', location: 'DC1-BK-01', again: false }], message: null };
const COUNTING: CountState = { counting: { id: 'c1', number: 'CC000001', location: 'DC1-BK-01', entries: [] }, waitingCount: 0, waiting: [], message: null };
const withEntry = (): CountState => ({
  ...COUNTING,
  counting: { ...COUNTING.counting!, entries: [{ id: 'e1', lpn: null, item: BAR, lotNumber: 'L1', serialNumber: null, quantity: 12 }] },
});

type Reply = { status?: number; body: unknown } | 'offline';
let sent: { method: string; path: string; body: Record<string, unknown> | null }[];
let replies: Record<string, Reply[]>;
let gets: Record<string, unknown>;

beforeEach(() => {
  sent = [];
  replies = {};
  gets = { '/api/rf/session': USER, '/api/rf/counts': NONE };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (path: string, init: RequestInit = {}) => {
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      const method = init.method ?? 'GET';
      if (method === 'GET') return path in gets ? json(gets[path]) : json({ detail: 'Not known', errors: { code: 'x' } }, 400);
      sent.push({ method, path, body: init.body ? JSON.parse(String(init.body)) : null });
      const reply = replies[path]?.shift();
      if (reply === 'offline') throw new TypeError('Failed to fetch');
      return reply ? json(reply.body, reply.status) : json({ detail: 'Not found' }, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function open(path: string) {
  render(
    <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/rf/*" element={<RfApp />} />
      </Routes>
    </MemoryRouter>,
  );
}

async function scan(user: UserEvent, label: RegExp | string, value: string) {
  await user.type(await screen.findByLabelText(label), `${value}{Enter}`);
}

describe('Move', () => {
  it('moves an LPN whole, asking when there is no room', async () => {
    const user = userEvent.setup();
    gets['/api/rf/moves/from/p1'] = PALLET;
    open('/rf/move');
    await scan(user, 'LPN or location', 'nope');
    expect(await screen.findByRole('alert')).toHaveTextContent('Not known');
    await scan(user, 'LPN or location', 'p1');
    expect(await screen.findByLabelText('In it')).toHaveTextContent('SKU-BAR60 EA');
    expect(screen.getByLabelText('Move to location')).toHaveFocus();
    replies['/api/rf/moves/lpn'] = [
      { status: 409, body: { detail: 'DC1-RS-02 has no room for another LPN (it holds 1).', errors: { capacity: 'x' } } },
      { body: { message: 'P1 is at DC1-RS-02' } },
    ];
    await scan(user, 'Move to location', 'dc1-rs-02');
    await user.click(within(await screen.findByRole('group', { name: 'No room' })).getByRole('button', { name: 'Put it there anyway' }));
    expect(await screen.findByRole('status')).toHaveTextContent('P1 is at DC1-RS-02.');
    expect(sent[1].body).toEqual({ lpnId: 'P1', toLocationId: 'dc1-rs-02', allowOver: true });
    expect(screen.getByLabelText('LPN or location')).toHaveFocus();
  });

  it('moves some of what lies loose, in the scanned unit', async () => {
    const user = userEvent.setup();
    gets['/api/rf/moves/from/dc1-bk-01'] = SHELF;
    open('/rf/move');
    await scan(user, 'LPN or location', 'dc1-bk-01');
    expect(await screen.findByLabelText('Loose here')).toHaveTextContent('lot L1 · 20 reserved');
    await scan(user, 'Item', '999');
    expect(await screen.findByRole('alert')).toHaveTextContent('999 is not loose at DC1-BK-01');
    await scan(user, 'Item', '111006');
    await scan(user, 'Quantity (CS of 6 EA), up to 6', '7');
    expect(await screen.findByRole('alert')).toHaveTextContent('Only 40 EA can be moved');
    await scan(user, /Quantity/, '6');
    expect(await screen.findByText('36 EA')).toBeInTheDocument();
    replies['/api/rf/moves/loose'] = ['offline', { body: { message: 'Moved 36 EA of SKU-BAR to DC1-BK-03' } }];
    await scan(user, 'Move to location', 'DC1-BK-03');
    expect(await screen.findByRole('alert')).toHaveTextContent("Can't reach the WMS");
    await scan(user, 'Move to location', 'DC1-BK-03');
    expect(await screen.findByRole('status')).toHaveTextContent('Moved 36 EA');
    expect(sent[1].body).toMatchObject({ inventoryId: 's1', quantity: 36, toLocationId: 'DC1-BK-03' });
    expect(sent[1].body!.requestId).toBe(sent[0].body!.requestId);
  });

  it('asks which when the item is there in several lots', async () => {
    const user = userEvent.setup();
    gets['/api/rf/moves/from/dc1-bk-01'] = { ...SHELF, lines: [LINE, { ...LINE, inventoryId: 's2', lotNumber: 'L2', quantity: 5, free: 5 }] };
    open('/rf/move');
    await scan(user, 'LPN or location', 'dc1-bk-01');
    await scan(user, 'Item', '111000');
    await user.click(await screen.findByRole('button', { name: /lot L2/ }));
    expect(await screen.findByLabelText('Quantity (EA), up to 5')).toBeInTheDocument();
  });
});

describe('Moving off a pack station', () => {
  const TOTE: MoveSource = { lpn: 'T1', containerType: 'Tote', location: 'DC1-PACK-01', whole: true, byItem: true, whyStays: null, lines: [{ ...LINE, quantity: 12, free: 12 }], lpns: [] };
  const IN_TOTE = { location: 'DC1-PACK-01', lpn: 'T1', items: ['SKU-BAR'], quantity: 12, held: 0 };

  it('takes things out of an LPN', async () => {
    const user = userEvent.setup();
    gets['/api/rf/moves/put-back'] = [IN_TOTE, { location: 'DC1-PACK-02', lpn: null, items: ['SKU-BAR'], quantity: 3, held: 3 }];
    gets['/api/rf/moves/from/T1'] = TOTE;
    open('/rf/move');
    expect(await screen.findByRole('button', { name: /DC1-PACK-02/ })).toHaveTextContent('3 EAloose · SKU-BAR · 3 on hold');
    await user.click(screen.getByRole('button', { name: /T1/ }));
    expect(await screen.findByLabelText('To put back')).toHaveTextContent('SKU-BAR');
    await scan(user, 'Item', '999');
    expect(await screen.findByRole('alert')).toHaveTextContent('999 is not in T1');
    await scan(user, 'Item', '111000');
    await scan(user, 'Quantity (EA), up to 12', '12');
    gets['/api/rf/moves/put-back'] = [];
    replies['/api/rf/moves/loose'] = [{ body: { message: 'Moved 12 EA of SKU-BAR to DC1-BK-03' } }];
    await scan(user, 'Move to location', 'DC1-BK-03');
    expect(await screen.findByRole('status')).toHaveTextContent('Moved 12 EA');
    expect(sent[0]).toMatchObject({ path: '/api/rf/moves/loose', body: { inventoryId: 's1', quantity: 12 } });
    await vi.waitFor(() => expect(screen.queryByLabelText('To put back')).not.toBeInTheDocument());
  });

  it('moves an LPN as it is', async () => {
    const user = userEvent.setup();
    gets['/api/rf/moves/from/t1'] = TOTE;
    open('/rf/move');
    await scan(user, 'LPN or location', 't1');
    await user.click(await screen.findByRole('button', { name: 'Move T1 as it is' }));
    replies['/api/rf/moves/lpn'] = [{ body: { message: 'T1 is at DC1-BK-03' } }];
    await scan(user, 'Move to location', 'DC1-BK-03');
    expect(await screen.findByRole('status')).toHaveTextContent('T1 is at DC1-BK-03.');
  });

  it('says why an LPN stays, and what is at a station', async () => {
    const user = userEvent.setup();
    gets['/api/rf/moves/from/dc1-pack-01'] = { ...TOTE, lpn: null, containerType: null, whole: false, byItem: false, lines: [], lpns: [IN_TOTE] };
    gets['/api/rf/moves/from/T1'] = { ...TOTE, whole: false, whyStays: 'T1 is waiting to be packed (PK000001)' };
    open('/rf/move');
    await scan(user, 'LPN or location', 'dc1-pack-01');
    await user.click(await screen.findByRole('button', { name: /T1/ }));
    expect(await screen.findByText("T1 is waiting to be packed (PK000001), so it can't be moved as it is.")).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /as it is/ })).not.toBeInTheDocument();
  });
});

describe('Count', () => {
  it('counts a location without showing what is expected', async () => {
    const user = userEvent.setup();
    open('/rf/count');
    expect(await screen.findByText('Go to DC1-BK-01')).toBeInTheDocument();
    replies['/api/rf/counts/start'] = [{ body: COUNTING }];
    await scan(user, 'Location', 'dc1-bk-01');
    gets['/api/rf/counts/items/111006'] = { item: BAR, scan: SCANS[1] };
    await scan(user, 'Item', '111006');
    await scan(user, 'Lot', 'l1');
    replies['/api/rf/counts/c1/entries'] = [{ body: withEntry() }];
    await scan(user, 'How many (CS of 6 EA)', '2');
    expect(await screen.findByRole('status')).toHaveTextContent('Counted 2 CS of SKU-BAR');
    expect(screen.getByLabelText('Counted so far')).toHaveTextContent('SKU-BAR 12 EAlot L1');
    expect(sent[1].body).toMatchObject({ lpnId: null, itemBarcode: '111006', quantity: 2, lotNumber: 'L1', serialNumbers: [] });
    expect(screen.getByLabelText('Item')).toHaveFocus();
    await user.click(screen.getByRole('button', { name: 'Finish' }));
    expect(screen.getByText('What you did not count will be taken as missing.')).toBeInTheDocument();
    replies['/api/rf/counts/c1/finish'] = [{ body: { ...NONE, waitingCount: 0, waiting: [], message: 'DC1-BK-01 is counted. A supervisor will review it.' } }];
    await user.click(screen.getByRole('button', { name: 'Yes, everything' }));
    expect(await screen.findByRole('status')).toHaveTextContent('A supervisor will review it');
  });

  it('counts serial numbers inside an LPN', async () => {
    const user = userEvent.setup();
    gets['/api/rf/counts'] = COUNTING;
    gets['/api/rf/counts/items/222000'] = { item: DRILL, scan: { value: '222000', uom: 'EA', eachesPerUnit: 1 } };
    open('/rf/count');
    await user.click(await screen.findByRole('button', { name: 'Count inside an LPN' }));
    await scan(user, 'LPN', 'p1');
    await scan(user, 'Item', '222000');
    await scan(user, 'Serial number 1', 'd1');
    await scan(user, 'Serial number 2', 'D1');
    expect(await screen.findByRole('alert')).toHaveTextContent('D1 is already scanned');
    await scan(user, 'Serial number 2', 'd2');
    replies['/api/rf/counts/c1/entries'] = [{ body: withEntry() }];
    await user.click(await screen.findByRole('button', { name: 'Count 2' }));
    await screen.findByRole('status');
    expect(sent[0].body).toMatchObject({ lpnId: 'P1', quantity: null, serialNumbers: ['D1', 'D2'] });
    expect(screen.getByText('Scan an item in P1')).toBeInTheDocument();
  });

  it('keeps an entry that got no answer and sends it again under the same ID', async () => {
    const user = userEvent.setup();
    gets['/api/rf/counts'] = COUNTING;
    gets['/api/rf/counts/items/111000'] = { item: { ...BAR, lotControlled: false }, scan: SCANS[0] };
    open('/rf/count');
    await scan(user, 'Item', '111000');
    replies['/api/rf/counts/c1/entries'] = ['offline', { body: withEntry() }];
    await scan(user, 'How many (EA)', '12');
    await user.click(await screen.findByRole('button', { name: 'Send again' }));
    await screen.findByLabelText('Counted so far');
    expect(sent[1].body!.requestId).toBe(sent[0].body!.requestId);
  });

  it('removes a mistake, and gives a count back', async () => {
    const user = userEvent.setup();
    gets['/api/rf/counts'] = withEntry();
    open('/rf/count');
    replies['/api/rf/counts/c1/entries/e1'] = [{ body: COUNTING }];
    await user.click(await screen.findByRole('button', { name: 'Remove 12 EA of SKU-BAR' }));
    await vi.waitFor(() => expect(screen.queryByLabelText('Counted so far')).not.toBeInTheDocument());
    expect(sent[0].method).toBe('DELETE');
    await user.click(screen.getByRole('button', { name: 'Finish' }));
    expect(screen.getByRole('button', { name: 'Yes, it is empty' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Keep counting' }));
    replies['/api/rf/counts/c1/give-back'] = [{ body: { ...NONE, message: 'Given back' } }];
    await user.click(screen.getByRole('button', { name: 'Give this count back' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Given back');
  });

  it('asks before taking over', async () => {
    const user = userEvent.setup();
    open('/rf/count');
    replies['/api/rf/counts/start'] = [{ status: 409, body: { detail: 'Bob is counting DC1-BK-01.', errors: { assignedTo: 'Bob' } } }, { body: COUNTING }];
    await scan(user, 'Location', 'dc1-bk-01');
    await user.click(await screen.findByRole('button', { name: 'Take over DC1-BK-01' }));
    expect(await screen.findByText('Scan an item lying loose')).toBeInTheDocument();
    expect(sent[1].body).toEqual({ locationId: 'dc1-bk-01', takeOver: true });
  });
});
