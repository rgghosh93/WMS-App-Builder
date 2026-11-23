import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../../api/http';
import { addLocations, previewLocations, zoneFunctionLabel, type AddPreview, type LocationFilter, type Zone } from '../../api/zones';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';

const ADDRESS = [
  { field: 'area', label: 'Area', max: 20 },
  { field: 'zone', label: 'Zone in the address', max: 20 },
  { field: 'aisleFrom', label: 'Aisle from', max: 20 },
  { field: 'aisleTo', label: 'Aisle to', max: 20 },
  { field: 'bayFrom', label: 'Bay from', max: 20 },
  { field: 'bayTo', label: 'Bay to', max: 20 },
  { field: 'level', label: 'Level', max: 20 },
] as const;

type AddressField = (typeof ADDRESS)[number]['field'];

/** Puts storage locations in a zone: those of part of the address, or a list of them. Says first what that will do. */
export function AddZoneLocationsForm({ zone, onClose, onDone }: { zone: Zone; onClose: () => void; onDone: (added: number) => void }) {
  const [by, setBy] = useState<'address' | 'ids'>('address');
  const [address, setAddress] = useState<Partial<Record<AddressField, string>>>({});
  const [ids, setIds] = useState('');
  const [move, setMove] = useState(false);
  const [preview, setPreview] = useState<AddPreview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const filter = toFilter(by, address, ids);
  const key = filter ? JSON.stringify(filter) : null;

  useEffect(() => {
    setPreview(null);
    setError(null);
    setMove(false);
    if (!key) return;
    const controller = new AbortController();
    const handle = setTimeout(() => {
      previewLocations(zone.id, JSON.parse(key) as LocationFilter, controller.signal)
        .then(setPreview)
        .catch((e) => {
          if (!controller.signal.aborted) setError(message(e));
        });
    }, 300);
    return () => {
      clearTimeout(handle);
      controller.abort();
    };
  }, [key, zone.id]);

  const taken = preview ? Object.entries(preview.elsewhere) : [];
  const takenCount = taken.reduce((n, [, count]) => n + count, 0);
  const adding = preview ? preview.matching - preview.already : 0;
  const ready = filter && adding > 0 && (takenCount === 0 || move);

  const send = async (event: FormEvent) => {
    event.preventDefault();
    if (!filter || !ready) return;
    setSaving(true);
    setError(null);
    try {
      await addLocations(zone.id, filter, move);
      onDone(adding);
    } catch (e) {
      setError(message(e));
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={`Add locations to ${zone.code}`}
      onClose={onClose}
      permission="MASTER_DATA"
      footer={
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" form="add-locations-form" className="button button--primary" disabled={saving || !ready}>
            {saving ? 'Adding…' : adding > 0 ? `Add ${adding.toLocaleString()} location${adding === 1 ? '' : 's'}` : 'Add locations'}
          </button>
        </div>
      }
    >
      <form id="add-locations-form" className="form" onSubmit={send} noValidate>
        <Field id="add-by" label="Choose by">
          <select id="add-by" value={by} onChange={(e) => setBy(e.target.value as 'address' | 'ids')}>
            <option value="address">Address</option>
            <option value="ids">Location IDs</option>
          </select>
        </Field>
        {by === 'address' ? (
          <>
            <p className="field__hint">The area is needed. Leave the rest blank for all of it; ranges include both ends.</p>
            <div className="grid grid--2">
              {ADDRESS.map(({ field, label, max }) => (
                <Field key={field} id={`add-${field}`} label={label}>
                  <input
                    id={`add-${field}`}
                    className="input--code"
                    value={address[field] ?? ''}
                    maxLength={max}
                    autoComplete="off"
                    onChange={(e) => setAddress((a) => ({ ...a, [field]: e.target.value.toUpperCase() }))}
                  />
                </Field>
              ))}
            </div>
          </>
        ) : (
          <Field id="add-ids" label="Location IDs" hint="Separated by commas, spaces or lines">
            <textarea id="add-ids" className="input--code" rows={5} value={ids} onChange={(e) => setIds(e.target.value.toUpperCase())} />
          </Field>
        )}
        {error && (
          <div className="form__error" role="alert">
            {error}
          </div>
        )}
        {preview && (
          <div className="notice-info" role="status">
            {preview.matching === 0
              ? 'No storage locations match.'
              : `${preview.matching.toLocaleString()} storage location${preview.matching === 1 ? '' : 's'} match${preview.matching === 1 ? 'es' : ''}` +
                (preview.already > 0 ? `; ${preview.already.toLocaleString()} already here` : '') +
                (takenCount > 0 ? `; in another ${zoneFunctionLabel(zone.function).toLowerCase()} zone: ${taken.map(([code, n]) => `${n.toLocaleString()} in ${code}`).join(', ')}` : '') +
                '.'}
          </div>
        )}
        {takenCount > 0 && (
          <label className="check">
            <input type="checkbox" checked={move} onChange={(e) => setMove(e.target.checked)} />
            <span>
              <span className="check__label">
                Move {takenCount === 1 ? 'it' : 'them'} here from {taken.map(([code]) => code).join(', ')}
              </span>
              <span className="check__hint">A location is in one zone of each function</span>
            </span>
          </label>
        )}
      </form>
    </Drawer>
  );
}

/** What to look for, or null if not enough has been said. */
function toFilter(by: 'address' | 'ids', address: Partial<Record<AddressField, string>>, ids: string): LocationFilter | null {
  if (by === 'ids') {
    const list = ids.split(/[^A-Z0-9._-]+/).filter(Boolean);
    return list.length > 0 ? { locationIds: list } : null;
  }
  const parts = Object.fromEntries(Object.entries(address).filter(([, v]) => v?.trim())) as LocationFilter;
  return parts.area ? parts : null;
}

function message(e: unknown): string {
  const ids = e instanceof ApiError ? e.fieldErrors.locationIds : undefined;
  return ids ? sentenceCase(ids) : errorMessage(e);
}
