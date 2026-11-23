import { useState, type FormEvent } from 'react';
import { planCounts, type CountPlan } from '../../../api/control';
import { Drawer } from '../../../components/Drawer';
import { Field } from '../../../components/Field';
import { OwnerSelect } from '../../../components/OwnerSelect';
import { ZoneSelect } from '../../../components/ZoneSelect';
import { useSubmit } from './useSubmit';

type Kind = 'counting' | 'address' | 'location' | 'item';

/** Plans a count of each storage location in a counting zone or part of the address, of one location, or of everywhere an item is. */
export function PlanCountsForm({ onClose, onDone }: { onClose: () => void; onDone: (planned: { created: number; skipped: number }) => void }) {
  const [kind, setKind] = useState<Kind>('counting');
  const [area, setArea] = useState('');
  const [zone, setZone] = useState('');
  const [zoneId, setZoneId] = useState('');
  const [code, setCode] = useState('');
  const [owner, setOwner] = useState('');
  const [includeEmpty, setIncludeEmpty] = useState(false);
  const { errors, formError, saving, submit } = useSubmit();

  const send = (event: FormEvent) => {
    event.preventDefault();
    const found: Record<string, string> = {};
    if (kind === 'counting') {
      if (!zoneId) found.zoneId = 'Choose a zone';
    } else if (kind === 'address') {
      if (!area.trim()) found.area = 'Enter the area';
      if (!zone.trim()) found.zone = 'Enter the zone';
    } else if (!code.trim()) found[`${kind}Id`] = `Enter the ${kind}`;
    const plan: CountPlan =
      kind === 'counting'
        ? { zoneId, includeEmpty }
        : kind === 'address'
          ? { area: area.trim(), zone: zone.trim(), includeEmpty }
          : kind === 'location'
            ? { locationId: code.trim() }
            : { itemId: code.trim(), ...(owner ? { owner } : {}) };
    void submit(found, async () => onDone(await planCounts(plan)));
  };

  const input = (id: string, label: string, value: string, set: (v: string) => void, error?: string, max = 40) => (
    <Field id={id} label={label} error={error}>
      <input id={id} className="input--code" value={value} maxLength={max} autoComplete="off" aria-invalid={Boolean(error)} onChange={(e) => set(e.target.value.toUpperCase())} />
    </Field>
  );

  return (
    <Drawer
      title="Plan counts"
      onClose={onClose}
      footer={
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" form="plan-form" className="button button--primary" disabled={saving}>
            {saving ? 'Planning…' : 'Plan counts'}
          </button>
        </div>
      }
    >
      <form id="plan-form" className="form" onSubmit={send} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <Field id="plan-kind" label="Count">
          <select id="plan-kind" value={kind} onChange={(e) => setKind(e.target.value as Kind)}>
            <option value="counting">A counting zone</option>
            <option value="address">A zone of the address</option>
            <option value="location">One location</option>
            <option value="item">Everywhere an item is</option>
          </select>
        </Field>
        {kind === 'counting' || kind === 'address' ? (
          <>
            {kind === 'counting' ? (
              <ZoneSelect id="plan-zone-id" fn="COUNTING" value={zoneId} onChange={setZoneId} error={errors.zoneId} none="Choose a zone" />
            ) : (
              <div className="grid grid--2">
                {input('plan-area', 'Area', area, setArea, errors.area, 20)}
                {input('plan-zone', 'Zone', zone, setZone, errors.zone, 20)}
              </div>
            )}
            <label className="check">
              <input type="checkbox" checked={includeEmpty} onChange={(e) => setIncludeEmpty(e.target.checked)} />
              <span>
                <span className="check__label">Empty locations too</span>
                <span className="check__hint">To find stock where none is recorded</span>
              </span>
            </label>
          </>
        ) : (
          <>
            {input('plan-code', kind === 'location' ? 'Location' : 'Item', code, setCode, errors.locationId ?? errors.itemId)}
            {kind === 'item' && <OwnerSelect id="plan-owner" value={owner} onChange={setOwner} error={errors.owner} none="Whoever has it" />}
          </>
        )}
      </form>
    </Drawer>
  );
}
