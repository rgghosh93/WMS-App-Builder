import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { createReason, deleteReason, getInventorySettings, listReasons, saveInventorySettings, updateReason, type Reason, type ReasonKind } from '../../../api/control';
import { ACTIVE_STATUSES, type ActiveStatus } from '../../../api/containerTypes';
import { errorMessage } from '../../../api/http';
import { Drawer } from '../../../components/Drawer';
import { Field } from '../../../components/Field';
import { Can, useCan } from '../../../session/SessionContext';
import { useSubmit } from './useSubmit';

type LoadState = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; reasons: Reason[]; limit: number };
type Editing = null | { mode: 'new' } | { mode: 'edit'; reason: Reason };

const KINDS: { value: ReasonKind; label: string }[] = [
  { value: 'ADJUSTMENT', label: 'Adjustments' },
  { value: 'HOLD', label: 'Holds and releases' },
];

/** Reasons for changing stock, and the limit above which a second person is needed. */
export function ReasonCodesPage() {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([listReasons(controller.signal), getInventorySettings(controller.signal)])
      .then(([reasons, settings]) => setState({ status: 'ready', reasons, limit: settings.approvalLimit }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [reloadKey]);

  useEffect(() => {
    if (!notice) return;
    const handle = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(handle);
  }, [notice]);

  const done = (message: string) => {
    setEditing(null);
    setNotice(message);
    setReloadKey((k) => k + 1);
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Inventory</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Reason Codes</span>
      </nav>
      <header className="page__header">
        <div>
          <h1>Reason Codes</h1>
          <p className="muted">Why stock is adjusted, held and released. History keeps the name a reason had at the time.</p>
        </div>
        <Can permission="INVENTORY_APPROVE">
          <button type="button" className="button button--primary" onClick={() => setEditing({ mode: 'new' })}>
            <Plus size={16} aria-hidden="true" />
            New reason
          </button>
        </Can>
      </header>
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
      {state.status === 'loading' && <p className="muted">Loading…</p>}
      {state.status === 'error' && (
        <div className="notice" role="alert">
          <p>{state.message}</p>
          <button type="button" className="button" onClick={() => setReloadKey((k) => k + 1)}>
            Try again
          </button>
        </div>
      )}
      {state.status === 'ready' && (
        <>
          <LimitForm key={state.limit} limit={state.limit} onSaved={(limit) => done(`The limit is ${limit.toLocaleString()} EA`)} />
          <div className="table-wrap table-wrap--narrow">
            <table className="table" aria-label="Reason codes">
              <thead>
                <tr>
                  <th scope="col">Code</th>
                  <th scope="col">Name</th>
                  <th scope="col">For</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {state.reasons.map((r) => (
                  <tr key={r.id} className="table__row--link" onClick={() => setEditing({ mode: 'edit', reason: r })}>
                    <th scope="row">
                      <button
                        type="button"
                        className="link-button code"
                        onClick={(e) => {
                          e.stopPropagation();
                          setEditing({ mode: 'edit', reason: r });
                        }}
                      >
                        {r.code}
                      </button>
                    </th>
                    <td>{r.name}</td>
                    <td>{KINDS.find((k) => k.value === r.kind)?.label}</td>
                    <td>
                      <span className={`badge badge--${r.status.toLowerCase()}`}>{r.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {editing && (
        <ReasonForm
          key={editing.mode === 'edit' ? editing.reason.id : 'new'}
          reason={editing.mode === 'edit' ? editing.reason : null}
          onClose={() => setEditing(null)}
          onDone={done}
        />
      )}
    </div>
  );
}

function LimitForm({ limit, onSaved }: { limit: number; onSaved: (limit: number) => void }) {
  const [value, setValue] = useState(String(limit));
  const may = useCan('INVENTORY_APPROVE');
  const { errors, formError, saving, submit } = useSubmit();
  const send = (event: FormEvent) => {
    event.preventDefault();
    void submit(/^[0-9]+$/.test(value.trim()) ? {} : { approvalLimit: 'Enter a whole number' }, async () =>
      onSaved((await saveInventorySettings(Number(value))).approvalLimit),
    );
  };
  return (
    <form className="limit-form" onSubmit={send} noValidate>
      <Field
        id="approval-limit"
        label="Approval limit (EA)"
        error={errors.approvalLimit ?? (formError || undefined)}
        hint="Adjustments of more than this, and counts that differ by more in all, need a second person. 0 means always."
      >
        <input id="approval-limit" type="number" inputMode="numeric" min="0" step="1" value={value} disabled={!may} onChange={(e) => setValue(e.target.value)} />
      </Field>
      {may && (
        <button type="submit" className="button" disabled={saving || value.trim() === String(limit)}>
          Save limit
        </button>
      )}
    </form>
  );
}

function ReasonForm({ reason, onClose, onDone }: { reason: Reason | null; onClose: () => void; onDone: (message: string) => void }) {
  const [kind, setKind] = useState<ReasonKind>(reason?.kind ?? 'ADJUSTMENT');
  const [code, setCode] = useState(reason?.code ?? '');
  const [name, setName] = useState(reason?.name ?? '');
  const [status, setStatus] = useState<ActiveStatus>(reason?.status ?? 'ACTIVE');
  const { errors, formError, saving, submit } = useSubmit();

  const send = (event: FormEvent) => {
    event.preventDefault();
    const input = { kind, code: code.trim(), name: name.trim(), status, version: reason?.version };
    void submit({ ...(code.trim() ? {} : { code: 'Enter a code' }), ...(name.trim() ? {} : { name: 'Enter a name' }) }, async () => {
      if (reason) await updateReason(reason.id, input);
      else await createReason(input);
      onDone(`Saved reason ${input.code}`);
    });
  };

  return (
    <Drawer
      title={reason ? `Edit ${reason.code}` : 'New reason'}
      onClose={onClose}
      permission="INVENTORY_APPROVE"
      footer={
        <>
          {reason && (
            <button
              type="button"
              className="button button--danger-quiet"
              disabled={saving}
              onClick={() =>
                void submit({}, async () => {
                  await deleteReason(reason.id);
                  onDone(`Deleted reason ${reason.code}`);
                })
              }
            >
              Delete
            </button>
          )}
          <div className="button-row button-row--end">
            <button type="button" className="button" onClick={onClose} disabled={saving}>
              Cancel
            </button>
            <button type="submit" form="reason-form" className="button button--primary" disabled={saving}>
              {reason ? 'Save changes' : 'Create reason'}
            </button>
          </div>
        </>
      }
    >
      <form id="reason-form" className="form" onSubmit={send} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <Field id="reason-kind" label="For">
          <select id="reason-kind" value={kind} disabled={reason !== null} onChange={(e) => setKind(e.target.value as ReasonKind)}>
            {KINDS.map((k) => (
              <option key={k.value} value={k.value}>
                {k.label}
              </option>
            ))}
          </select>
        </Field>
        <Field id="reason-code" label="Code" error={errors.code}>
          <input id="reason-code" className="input--code" value={code} maxLength={20} autoComplete="off" onChange={(e) => setCode(e.target.value.toUpperCase())} />
        </Field>
        <Field id="reason-name" label="Name" error={errors.name}>
          <input id="reason-name" value={name} maxLength={80} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field id="reason-status" label="Status">
          <select id="reason-status" value={status} onChange={(e) => setStatus(e.target.value as ActiveStatus)}>
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
