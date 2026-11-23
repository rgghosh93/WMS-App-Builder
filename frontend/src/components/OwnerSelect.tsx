import { useOwners, type OwnerRef } from '../api/owners';
import { Field } from './Field';
import { FilterSelect } from './ListPage';

interface OwnerSelectProps {
  id: string;
  value: string;
  onChange: (code: string) => void;
  error?: string;
  hint?: string;
  /** What choosing none means; without it, the first owner is meant. */
  none?: string;
}

/** Chooses whose something is, by code. Not there while there is only one owner. */
export function OwnerSelect({ id, value, onChange, error, hint, none }: OwnerSelectProps) {
  const { owners, several } = useOwners();
  if (!several) return null;
  const offered = owners.filter((o) => o.status === 'ACTIVE' || o.code === value);
  return (
    <Field id={id} label="Owner" error={error} hint={hint}>
      <select id={id} value={value} aria-invalid={Boolean(error)} onChange={(e) => onChange(e.target.value)}>
        <option value="">{none ?? 'Choose…'}</option>
        {offered.map((o) => (
          <option key={o.id} value={o.code}>
            {o.code} · {o.name}
          </option>
        ))}
      </select>
    </Field>
  );
}

/** Whose something is, among its facts. Not there while there is only one owner. */
export function OwnerFact({ owner }: { owner?: OwnerRef | null }) {
  const { several } = useOwners();
  if (!several || !owner) return null;
  return (
    <div>
      <dt>Owner</dt>
      <dd>
        <span className="code">{owner.code}</span> {owner.name}
      </dd>
    </div>
  );
}

/** Filters a list by owner. Not there while there is only one owner. */
export function OwnerFilter({ value, onChange }: { value: string; onChange: (code: string) => void }) {
  const { owners, several } = useOwners();
  if (!several && !value) return null;
  return <FilterSelect label="Owner" value={value} options={owners.map((o) => ({ value: o.code, label: o.code }))} onChange={onChange} />;
}
