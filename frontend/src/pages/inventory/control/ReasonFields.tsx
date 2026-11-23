import { useEffect, useState } from 'react';
import { listReasons, type Reason, type ReasonKind } from '../../../api/control';
import { Field } from '../../../components/Field';

/** The active reasons of a kind. */
export function useReasons(kind: ReasonKind): Reason[] {
  const [reasons, setReasons] = useState<Reason[]>([]);
  useEffect(() => {
    const controller = new AbortController();
    listReasons(controller.signal)
      .then((all) => setReasons(all.filter((r) => r.kind === kind && r.status === 'ACTIVE')))
      .catch(() => undefined); // the form says a reason is needed, and the server refuses without one
    return () => controller.abort();
  }, [kind]);
  return reasons;
}

interface ReasonFieldsProps {
  id: string;
  kind: ReasonKind;
  reasonId: string;
  note: string;
  error?: string;
  onReason: (id: string) => void;
  onNote: (note: string) => void;
}

export function ReasonFields({ id, kind, reasonId, note, error, onReason, onNote }: ReasonFieldsProps) {
  const reasons = useReasons(kind);
  return (
    <>
      <Field id={`${id}-reason`} label="Reason" error={error}>
        <select id={`${id}-reason`} value={reasonId} aria-invalid={Boolean(error)} onChange={(e) => onReason(e.target.value)}>
          <option value="">Choose…</option>
          {reasons.map((r) => (
            <option key={r.id} value={r.id}>
              {r.name}
            </option>
          ))}
        </select>
      </Field>
      <Field id={`${id}-note`} label="Note" hint="Optional">
        <input id={`${id}-note`} value={note} maxLength={250} onChange={(e) => onNote(e.target.value)} />
      </Field>
    </>
  );
}
