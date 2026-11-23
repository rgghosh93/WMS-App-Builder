import { useEffect, useState } from 'react';
import { errorMessage } from '../api/http';
import { listZones, zoneFunctionLabel, type Zone, type ZoneFunction } from '../api/zones';
import { Field } from './Field';

interface ZoneSelectProps {
  id: string;
  fn: ZoneFunction;
  value: string;
  onChange: (id: string) => void;
  error?: string;
  hint?: string;
  /** What choosing none means. */
  none: string;
}

/** Chooses a zone of one function. Inactive ones are only offered if already chosen. */
export function ZoneSelect({ id, fn, value, onChange, error, hint, none }: ZoneSelectProps) {
  const [zones, setZones] = useState<Zone[] | null>(null);
  const [failed, setFailed] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listZones(fn, controller.signal)
      .then(setZones)
      .catch((e) => {
        if (!controller.signal.aborted) setFailed(errorMessage(e));
      });
    return () => controller.abort();
  }, [fn]);

  const offered = zones?.filter((z) => z.status === 'ACTIVE' || z.id === value) ?? [];
  const empty = zones && offered.length === 0 ? `No ${zoneFunctionLabel(fn).toLowerCase()} zones yet. Add one under Master Data → Zones.` : undefined;
  return (
    <Field id={id} label={`${zoneFunctionLabel(fn)} zone`} error={error ?? failed ?? undefined} hint={empty ?? hint}>
      <select id={id} value={zones ? value : ''} aria-invalid={Boolean(error)} disabled={!zones} onChange={(e) => onChange(e.target.value)}>
        <option value="">{zones || failed ? none : 'Loading…'}</option>
        {offered.map((z) => (
          <option key={z.id} value={z.id}>
            {z.code} · {z.name}
            {z.status === 'INACTIVE' ? ' (inactive)' : ''}
          </option>
        ))}
      </select>
    </Field>
  );
}
