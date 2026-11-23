import { useState, type FormEvent } from 'react';
import { ACTIVE_STATUSES, type ActiveStatus } from '../../api/containerTypes';
import { errorMessage } from '../../api/http';
import { listItems, type ItemUom } from '../../api/items';
import { listLocations } from '../../api/locations';
import type { ItemRef } from '../../api/orders';
import { deleteReplenishmentRule, saveReplenishmentRule, type ReplenishmentRule } from '../../api/replenishment';
import { Drawer } from '../../components/Drawer';
import { Field } from '../../components/Field';
import { SearchPicker } from '../../components/SearchPicker';
import { ZoneSelect } from '../../components/ZoneSelect';
import { positiveIntegerError } from '../../forms/validation';
import { useSubmit } from '../inventory/control/useSubmit';

interface ReplenishmentRuleFormProps {
  /** The rule to edit, or null to create a new one. */
  rule: ReplenishmentRule | null;
  onClose: () => void;
  onSaved: (rule: ReplenishmentRule, created: boolean) => void;
  onDeleted: (rule: ReplenishmentRule) => void;
}

type ItemChoice = ItemRef & { uoms?: ItemUom[]; serialControlled?: boolean };
type LocationRef = { id: string; locationId: string };

export function ReplenishmentRuleForm({ rule, onClose, onSaved, onDeleted }: ReplenishmentRuleFormProps) {
  const [item, setItem] = useState<ItemChoice | null>(rule?.item ?? null);
  const [location, setLocation] = useState<LocationRef | null>(rule?.location ?? null);
  const [min, setMin] = useState(rule ? String(rule.minQuantity) : '');
  const [max, setMax] = useState(rule ? String(rule.maxQuantity) : '');
  const [multiple, setMultiple] = useState(String(rule?.multiple ?? 1));
  const [zoneId, setZoneId] = useState(rule?.sourceZone?.id ?? '');
  const [forOrders, setForOrders] = useState(rule?.forOrders ?? true);
  const [status, setStatus] = useState<ActiveStatus>(rule?.status ?? 'ACTIVE');
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const { errors, formError, saving, submit } = useSubmit();

  const send = (event: FormEvent) => {
    event.preventDefault();
    setDeleteError(null);
    const found: Record<string, string> = {};
    if (!item) found.itemId = 'Choose an item';
    else if (item.serialControlled) found.itemId = "Items with serial numbers can't be replenished yet";
    if (!location) found.locationId = 'Choose a location';
    if (!/^[0-9]+$/.test(min.trim())) found.minQuantity = 'A whole number, 0 or more';
    const problem = positiveIntegerError(max);
    if (problem) found.maxQuantity = problem;
    else if (!found.minQuantity && Number(max) <= Number(min)) found.maxQuantity = 'Must be more than the minimum';
    const by = positiveIntegerError(multiple);
    if (by) found.multiple = by;
    else if (!found.minQuantity && !found.maxQuantity && Number(max) - Number(min) < Number(multiple)) found.multiple = 'Must fit between the minimum and the maximum';
    void submit(found, async () =>
      onSaved(
        await saveReplenishmentRule(rule?.id ?? null, {
          itemId: item!.id,
          locationId: location!.id,
          minQuantity: Number(min),
          maxQuantity: Number(max),
          multiple: Number(multiple),
          sourceZoneId: zoneId || null,
          forOrders,
          status,
          version: rule?.version,
        }),
        rule === null,
      ),
    );
  };

  const remove = async () => {
    if (!rule) return;
    setDeleteError(null);
    try {
      await deleteReplenishmentRule(rule.id);
      onDeleted(rule);
    } catch (error) {
      setDeleteError(errorMessage(error));
      setConfirmingDelete(false);
    }
  };

  const number = (id: string, label: string, value: string, set: (v: string) => void, error?: string, hint?: string) => (
    <Field id={id} label={label} error={error} hint={hint}>
      <input id={id} type="number" inputMode="numeric" min="0" step="1" value={value} aria-invalid={Boolean(error)} onChange={(e) => set(e.target.value)} />
    </Field>
  );

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete this rule? Its tasks nobody has started are called off.</span>
      <div className="button-row">
        <button type="button" className="button" onClick={() => setConfirmingDelete(false)}>
          Keep it
        </button>
        <button type="button" className="button button--danger" onClick={remove}>
          Delete
        </button>
      </div>
    </div>
  ) : (
    <>
      {rule && (
        <button type="button" className="button button--danger-quiet" onClick={() => setConfirmingDelete(true)} disabled={saving}>
          Delete
        </button>
      )}
      <div className="button-row button-row--end">
        <button type="button" className="button" onClick={onClose} disabled={saving}>
          Cancel
        </button>
        <button type="submit" form="replenishment-rule-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : rule ? 'Save changes' : 'Create rule'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={rule ? `Edit ${rule.item.itemId} at ${rule.location.locationId}` : 'New replenishment rule'} onClose={onClose} footer={footer} permission="REPLENISHMENT">
      <form id="replenishment-rule-form" className="form" onSubmit={send} noValidate>
        {(formError ?? deleteError) && (
          <div className="form__error" role="alert">
            {formError ?? deleteError}
          </div>
        )}
        <Field id="rep-item" label="Item" error={errors.itemId}>
          <SearchPicker<ItemChoice>
            id="rep-item"
            value={item}
            onChange={setItem}
            search={(text, signal) => listItems({ q: text, status: 'ACTIVE', page: 0, size: 8, sort: 'itemId', dir: 'asc' }, signal).then((p) => p.content)}
            optionKey={(i) => i.id}
            optionText={(i) => i.itemId}
            renderOption={(i) => (
              <>
                <span className="code">{i.itemId}</span> <span className="muted">{i.description}</span>
              </>
            )}
            invalid={Boolean(errors.itemId)}
            placeholder="Search items"
            clearLabel="Clear item"
            emptyText="No matching active items"
            uppercase
          />
        </Field>
        <Field id="rep-location" label="Pick location" error={errors.locationId}>
          <SearchPicker<LocationRef>
            id="rep-location"
            value={location}
            onChange={setLocation}
            search={(text, signal) => listLocations({ q: text, type: 'STORAGE', page: 0, size: 8, sort: 'locationId', dir: 'asc' }, signal).then((p) => p.content)}
            optionKey={(l) => l.id}
            optionText={(l) => l.locationId}
            invalid={Boolean(errors.locationId)}
            placeholder="Search storage locations"
            clearLabel="Clear location"
            emptyText="No matching storage locations"
            uppercase
          />
        </Field>
        <div className="grid grid--3">
          {number('rep-min', 'Minimum (EA)', min, setMin, errors.minQuantity, 'Below this, it is filled')}
          {number('rep-max', 'Maximum (EA)', max, setMax, errors.maxQuantity, 'Filled up to this')}
          {number('rep-multiple', 'In multiples of', multiple, setMultiple, errors.multiple, '1 for any quantity')}
        </div>
        {item?.uoms && item.uoms.length > 0 && (
          <p className="field__hint">
            Bring whole{' '}
            {item.uoms.map((u, i) => (
              <span key={u.id}>
                {i > 0 && ', '}
                <button type="button" className="link-button link-button--small" onClick={() => setMultiple(String(u.quantity))}>
                  {u.uom.uomCode} ({u.quantity.toLocaleString()} EA)
                </button>
              </span>
            ))}
          </p>
        )}
        <ZoneSelect
          id="rep-zone"
          fn="REPLENISHMENT"
          value={zoneId}
          onChange={setZoneId}
          error={errors.sourceZoneId}
          none="Any other storage location"
          hint="Where the stock comes from. Other pick locations of the item are never used."
        />
        <label className="check">
          <input type="checkbox" checked={forOrders} onChange={(e) => setForOrders(e.target.checked)} />
          <span>
            <span className="check__label">Also fill for orders that are short</span>
            <span className="check__hint">Comes before the rest, and the orders get the stock as soon as it is there</span>
          </span>
        </label>
        <Field id="rep-status" label="Status">
          <select id="rep-status" value={status} onChange={(e) => setStatus(e.target.value as ActiveStatus)}>
            {ACTIVE_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </Field>
      </form>
    </Drawer>
  );
}
