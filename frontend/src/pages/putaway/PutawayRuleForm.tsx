import { useState, type FormEvent } from 'react';
import { ACTIVE_STATUSES, CONTAINER_CATEGORIES, type ActiveStatus, type ContainerCategory } from '../../api/containerTypes';
import { ApiError, errorMessage } from '../../api/http';
import { INVENTORY_STATUSES, type InventoryStatus } from '../../api/inventory';
import { listItems } from '../../api/items';
import { listLocations } from '../../api/locations';
import type { ItemRef } from '../../api/orders';
import {
  HANDLINGS,
  STRATEGIES,
  createPutawayRule,
  deletePutawayRule,
  updatePutawayRule,
  type PutawayHandling,
  type PutawayRule,
  type PutawayRuleInput,
  type PutawayStrategy,
} from '../../api/putaway';
import { Drawer } from '../../components/Drawer';
import { Field, sentenceCase } from '../../components/Field';
import { SearchPicker } from '../../components/SearchPicker';
import { ZoneSelect } from '../../components/ZoneSelect';
import { positiveIntegerError } from '../../forms/validation';

interface PutawayRuleFormProps {
  /** The rule to edit, or null to create a new one. */
  rule: PutawayRule | null;
  nextSequence: number;
  onClose: () => void;
  onSaved: (rule: PutawayRule, created: boolean) => void;
  onDeleted: (rule: PutawayRule) => void;
}

type Errors = Partial<Record<string, string>>;
type LocationRef = { id: string; locationId: string };

export function PutawayRuleForm({ rule, nextSequence, onClose, onSaved, onDeleted }: PutawayRuleFormProps) {
  const [sequence, setSequence] = useState(String(rule?.sequence ?? nextSequence));
  const [name, setName] = useState(rule?.name ?? '');
  const [status, setStatus] = useState<ActiveStatus>(rule?.status ?? 'ACTIVE');
  const [item, setItem] = useState<ItemRef | null>(rule?.item ?? null);
  const [category, setCategory] = useState<ContainerCategory | ''>(rule?.containerCategory ?? '');
  const [stockStatus, setStockStatus] = useState<InventoryStatus | ''>(rule?.stockStatus ?? '');
  const [strategy, setStrategy] = useState<PutawayStrategy>(rule?.strategy ?? 'ZONE');
  const [location, setLocation] = useState<LocationRef | null>(rule?.location ?? null);
  const [zoneId, setZoneId] = useState(rule?.zone?.id ?? '');
  const [handling, setHandling] = useState<PutawayHandling>(rule?.handling ?? 'KEEP_LPN');
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const validate = (): Errors => {
    const found: Errors = {};
    const order = positiveIntegerError(sequence);
    if (order) found.sequence = order;
    if (!name.trim()) found.name = 'Enter a name';
    if (strategy === 'FIXED' && !location) found.locationId = 'Choose a location';
    if (strategy === 'ZONE' && !zoneId) found.zoneId = 'Choose a zone';
    return found;
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    const input: PutawayRuleInput = {
      sequence: Number(sequence),
      name: name.trim(),
      status,
      itemId: item?.id ?? null,
      containerCategory: category || null,
      stockStatus: stockStatus || null,
      strategy,
      locationId: strategy === 'FIXED' ? location!.id : null,
      zoneId: (strategy !== 'FIXED' && zoneId) || null,
      handling,
      version: rule?.version,
    };
    setSaving(true);
    try {
      onSaved(rule ? await updatePutawayRule(rule.id, input) : await createPutawayRule(input), rule === null);
    } catch (error) {
      if (error instanceof ApiError) {
        setErrors(Object.fromEntries(Object.entries(error.fieldErrors).map(([f, m]) => [f, sentenceCase(m)])));
        setFormError(error.message);
      } else {
        setFormError(errorMessage(error));
      }
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    if (!rule) return;
    setSaving(true);
    setFormError(null);
    try {
      await deletePutawayRule(rule.id);
      onDeleted(rule);
    } catch (error) {
      setFormError(errorMessage(error));
      setConfirmingDelete(false);
    } finally {
      setSaving(false);
    }
  };

  const footer = confirmingDelete ? (
    <div className="drawer__confirm" role="group" aria-label="Confirm delete">
      <span>Delete {rule?.name}? This can't be undone.</span>
      <div className="button-row">
        <button type="button" className="button" onClick={() => setConfirmingDelete(false)} disabled={saving}>
          Keep it
        </button>
        <button type="button" className="button button--danger" onClick={remove} disabled={saving}>
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
        <button type="submit" form="putaway-rule-form" className="button button--primary" disabled={saving}>
          {saving ? 'Saving…' : rule ? 'Save changes' : 'Create rule'}
        </button>
      </div>
    </>
  );

  return (
    <Drawer title={rule ? `Edit ${rule.name}` : 'New put-away rule'} onClose={onClose} footer={footer} permission="PUTAWAY_RULES">
      <form id="putaway-rule-form" className="form" onSubmit={submit} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <Field id="rule-name" label="Name" error={errors.name}>
          <input id="rule-name" value={name} maxLength={120} aria-invalid={Boolean(errors.name)} onChange={(e) => setName(e.target.value)} />
        </Field>
        <div className="grid grid--2">
          <Field id="rule-sequence" label="Order" error={errors.sequence} hint="Lower is tried first">
            <input
              id="rule-sequence"
              type="number"
              inputMode="numeric"
              min="1"
              step="1"
              value={sequence}
              aria-invalid={Boolean(errors.sequence)}
              onChange={(e) => setSequence(e.target.value)}
            />
          </Field>
          <Field id="rule-status" label="Status">
            <select id="rule-status" value={status} onChange={(e) => setStatus(e.target.value as ActiveStatus)}>
              {ACTIVE_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <fieldset className="fieldset fieldset--stack">
          <legend>For</legend>
          <p className="field__hint">Leave everything open for a rule that fits any LPN.</p>
          <Field id="rule-item" label="Item" error={errors.itemId} hint="LPNs holding nothing else">
            <SearchPicker<ItemRef>
              id="rule-item"
              value={item}
              onChange={setItem}
              search={(text, signal) =>
                listItems({ q: text, status: 'ACTIVE', page: 0, size: 8, sort: 'itemId', dir: 'asc' }, signal).then((p) => p.content)
              }
              optionKey={(i) => i.id}
              optionText={(i) => i.itemId}
              renderOption={(i) => (
                <>
                  <span className="code">{i.itemId}</span> <span className="muted">{i.description}</span>
                </>
              )}
              placeholder="Any item"
              clearLabel="Clear item"
              emptyText="No matching active items"
              uppercase
            />
          </Field>
          <div className="grid grid--2">
            <Field id="rule-category" label="Kind of container">
              <select id="rule-category" value={category} onChange={(e) => setCategory(e.target.value as ContainerCategory | '')}>
                <option value="">Any</option>
                {CONTAINER_CATEGORIES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field id="rule-stock" label="Stock status" hint="If any of it has this status">
              <select id="rule-stock" value={stockStatus} onChange={(e) => setStockStatus(e.target.value as InventoryStatus | '')}>
                <option value="">Any</option>
                {INVENTORY_STATUSES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
        </fieldset>

        <fieldset className="fieldset fieldset--stack">
          <legend>Goes to</legend>
          <Field id="rule-strategy" label="How to choose" hint={STRATEGIES.find((s) => s.value === strategy)?.hint}>
            <select id="rule-strategy" value={strategy} onChange={(e) => setStrategy(e.target.value as PutawayStrategy)}>
              {STRATEGIES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
          {strategy === 'FIXED' ? (
            <Field id="rule-location" label="Location" error={errors.locationId}>
              <SearchPicker<LocationRef>
                id="rule-location"
                value={location}
                onChange={setLocation}
                search={(text, signal) =>
                  listLocations({ q: text, type: 'STORAGE', page: 0, size: 8, sort: 'locationId', dir: 'asc' }, signal).then((p) => p.content)
                }
                optionKey={(l) => l.id}
                optionText={(l) => l.locationId}
                invalid={Boolean(errors.locationId)}
                placeholder="Search storage locations"
                clearLabel="Clear location"
                emptyText="No matching storage locations"
                uppercase
              />
            </Field>
          ) : (
            <ZoneSelect
              id="rule-zone"
              fn="PUTAWAY"
              value={zoneId}
              onChange={setZoneId}
              error={errors.zoneId}
              none={strategy === 'ZONE' ? 'Choose a zone' : 'Anywhere'}
            />
          )}
          <Field id="rule-handling" label="The LPN">
            <select id="rule-handling" value={handling} onChange={(e) => setHandling(e.target.value as PutawayHandling)}>
              {HANDLINGS.map((h) => (
                <option key={h.value} value={h.value}>
                  {h.label}
                </option>
              ))}
            </select>
          </Field>
        </fieldset>
      </form>
    </Drawer>
  );
}
