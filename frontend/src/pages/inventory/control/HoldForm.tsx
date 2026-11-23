import { useState, type FormEvent } from 'react';
import { placeHold, previewHold, type HoldResult, type HoldScope } from '../../../api/control';
import { Drawer } from '../../../components/Drawer';
import { Field } from '../../../components/Field';
import { ReasonFields } from './ReasonFields';
import { useSubmit } from './useSubmit';

type Kind = 'lpnId' | 'locationId' | 'itemId' | 'lot';

const KINDS: { value: Kind; label: string; field: string }[] = [
  { value: 'lpnId', label: 'An LPN', field: 'LPN' },
  { value: 'locationId', label: 'Everything at a location', field: 'Location' },
  { value: 'lot', label: 'A lot', field: 'Item' },
  { value: 'itemId', label: 'All of an item', field: 'Item' },
];

/** Puts stock on hold, showing first how much that is. */
export function HoldForm({ onClose, onDone }: { onClose: () => void; onDone: (result: HoldResult) => void }) {
  const [kind, setKind] = useState<Kind>('lpnId');
  const [code, setCode] = useState('');
  const [lot, setLot] = useState('');
  const [damaged, setDamaged] = useState(false);
  const [reasonId, setReasonId] = useState('');
  const [note, setNote] = useState('');
  const [preview, setPreview] = useState<HoldResult | null>(null);
  const { errors, formError, saving, submit } = useSubmit();

  const label = KINDS.find((k) => k.value === kind)!.field;
  const scope: HoldScope = kind === 'lot' ? { itemId: code.trim(), lotNumber: lot.trim() } : { [kind]: code.trim() };
  const error = errors.lpnId ?? errors.locationId ?? errors.itemId;

  const send = (event: FormEvent) => {
    event.preventDefault();
    const found = {
      ...(code.trim() ? {} : { [kind === 'lot' ? 'itemId' : kind]: `Enter the ${label === 'LPN' ? label : label.toLowerCase()}` }),
      ...(kind === 'lot' && !lot.trim() ? { lotNumber: 'Enter the lot' } : {}),
      ...(reasonId ? {} : { reasonId: 'Choose a reason' }),
    };
    void submit(found, async () => {
      if (!preview) setPreview(await previewHold(scope));
      else onDone(await placeHold({ scope, damaged, reasonId, note: note.trim() }));
    });
  };

  const changed = (set: () => void) => {
    set();
    setPreview(null);
  };

  return (
    <Drawer
      title="Put on hold"
      onClose={onClose}
      footer={
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" form="hold-form" className="button button--primary" disabled={saving}>
            {saving ? 'Working…' : preview ? `Hold ${preview.quantity.toLocaleString()} EA` : 'Check'}
          </button>
        </div>
      }
    >
      <form id="hold-form" className="form" onSubmit={send} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <Field id="hold-kind" label="What">
          <select id="hold-kind" value={kind} onChange={(e) => changed(() => setKind(e.target.value as Kind))}>
            {KINDS.map((k) => (
              <option key={k.value} value={k.value}>
                {k.label}
              </option>
            ))}
          </select>
        </Field>
        <Field id="hold-code" label={label} error={error}>
          <input
            id="hold-code"
            className="input--code"
            value={code}
            maxLength={40}
            autoComplete="off"
            aria-invalid={Boolean(error)}
            onChange={(e) => changed(() => setCode(e.target.value.toUpperCase()))}
          />
        </Field>
        {kind === 'lot' && (
          <Field id="hold-lot" label="Lot" error={errors.lotNumber}>
            <input id="hold-lot" className="input--code" value={lot} maxLength={40} autoComplete="off" onChange={(e) => changed(() => setLot(e.target.value.toUpperCase()))} />
          </Field>
        )}
        <Field id="hold-as" label="As">
          <select id="hold-as" value={damaged ? 'DAMAGED' : 'ON_HOLD'} onChange={(e) => setDamaged(e.target.value === 'DAMAGED')}>
            <option value="ON_HOLD">On hold</option>
            <option value="DAMAGED">Damaged</option>
          </select>
        </Field>
        <ReasonFields id="hold" kind="HOLD" reasonId={reasonId} note={note} error={errors.reasonId} onReason={setReasonId} onNote={setNote} />
        {preview && (
          <p className="notice-info" role="status">
            {preview.quantity.toLocaleString()} EA of good stock in {preview.records} place{preview.records === 1 ? '' : 's'}.
            {preview.stuck > 0 &&
              ` ${preview.stuck.toLocaleString()} EA are reserved: orders nobody has started picking get other stock if there is any; the rest can’t be picked, packed or shipped until this is released.`}
          </p>
        )}
      </form>
    </Drawer>
  );
}
