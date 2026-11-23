import { listStock, type Stock } from '../../../api/control';
import { inventoryStatusLabel } from '../../../api/inventory';
import { SearchPicker } from '../../../components/SearchPicker';

export function stockPlace(s: Stock): string {
  return s.lpn ? `${s.lpn} at ${s.location ?? 'nowhere'}` : (s.location ?? 'nowhere');
}

export function stockDetail(s: Stock): string {
  return [s.lotNumber && `lot ${s.lotNumber}`, s.serialNumber, s.status !== 'AVAILABLE' && inventoryStatusLabel(s.status)].filter(Boolean).join(' · ');
}

/** Chooses one inventory record, by item, lot, LPN or location. */
export function StockPicker({ id, value, invalid, onChange }: { id: string; value: Stock | null; invalid?: boolean; onChange: (stock: Stock | null) => void }) {
  return (
    <SearchPicker<Stock>
      id={id}
      value={value}
      onChange={onChange}
      search={(text, signal) => listStock(new URLSearchParams({ q: text, page: '0', size: '10' }), signal).then((p) => p.content)}
      optionKey={(s) => s.id}
      optionText={(s) => `${s.item.itemId} · ${stockPlace(s)}`}
      renderOption={(s) => (
        <>
          <span className="code">{s.item.itemId}</span> {s.quantity.toLocaleString()} EA{' '}
          <span className="muted">
            {stockPlace(s)} {stockDetail(s)}
          </span>
        </>
      )}
      invalid={invalid}
      placeholder="Search item, lot, LPN or location"
      clearLabel="Clear stock"
      emptyText="No matching stock"
      uppercase
    />
  );
}
