import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import { ACTIVE_STATUSES, type ActiveStatus } from '../../api/containerTypes';
import { errorMessage } from '../../api/http';
import type { Permission } from '../../api/session';
import { Drawer } from '../../components/Drawer';
import { Field } from '../../components/Field';
import { Can } from '../../session/SessionContext';
import { useSubmit } from '../inventory/control/useSubmit';

interface SimpleRecord {
  id: string;
  code: string;
  name: string;
  status: ActiveStatus;
  version: number;
}

type Input = { code: string; name: string; status: ActiveStatus; version?: number };

interface SimpleRecordsProps<T extends SimpleRecord> {
  title: string;
  noun: string;
  nouns: string;
  intro: string;
  permission: Permission;
  /** Must not change between renders. */
  load: (signal: AbortSignal) => Promise<T[]>;
  save: (id: string | null, input: Input) => Promise<T>;
  /** One is opened to change, or a new one. */
  onOpen?: (record: T | null) => void;
  extraColumn?: { label: string; value: (record: T) => string | null };
  extraField?: { id: string; label: string; maxLength: number; value: string; onChange: (value: string) => void };
}

type LoadState<T> = { status: 'loading' } | { status: 'error'; message: string } | { status: 'ready'; records: T[] };

const CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

/** A short list of things with a code, a name and a status, shown whole, and the form to change one. */
export function SimpleRecords<T extends SimpleRecord>(props: SimpleRecordsProps<T>) {
  const { title, noun, nouns, intro, permission, load, extraColumn } = props;
  const [state, setState] = useState<LoadState<T>>({ status: 'loading' });
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<null | { record: T | null }>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal)
      .then((records) => setState({ status: 'ready', records }))
      .catch((error) => {
        if (!controller.signal.aborted) setState({ status: 'error', message: errorMessage(error) });
      });
    return () => controller.abort();
  }, [load, reloadKey]);

  useEffect(() => {
    if (!notice) return;
    const handle = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(handle);
  }, [notice]);

  const open = (record: T | null) => {
    props.onOpen?.(record);
    setEditing({ record });
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Master Data</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">{title}</span>
      </nav>
      <header className="page__header">
        <div>
          <h1>{title}</h1>
          <p className="muted">{intro}</p>
        </div>
        <Can permission={permission}>
          <button type="button" className="button button--primary" onClick={() => open(null)}>
            <Plus size={16} aria-hidden="true" />
            New {noun}
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
        <div className="table-wrap">
          <table className="table" aria-label={title}>
            <thead>
              <tr>
                <th scope="col">Code</th>
                <th scope="col">Name</th>
                {extraColumn && <th scope="col">{extraColumn.label}</th>}
                <th scope="col">Status</th>
              </tr>
            </thead>
            <tbody>
              {state.records.map((r) => (
                <tr key={r.id} className="table__row--link" onClick={() => open(r)}>
                  <th scope="row">
                    <button
                      type="button"
                      className="link-button code"
                      onClick={(e) => {
                        e.stopPropagation();
                        open(r);
                      }}
                    >
                      {r.code}
                    </button>
                  </th>
                  <td>{r.name}</td>
                  {extraColumn && <td className="cell--wrap">{extraColumn.value(r) ?? <span className="muted">—</span>}</td>}
                  <td>
                    <span className={`badge badge--${r.status.toLowerCase()}`}>{r.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {state.records.length === 0 && (
            <div className="empty">
              <p>No {nouns} yet.</p>
            </div>
          )}
        </div>
      )}
      {editing && (
        <RecordForm
          key={editing.record?.id ?? 'new'}
          record={editing.record}
          noun={noun}
          permission={permission}
          extraField={props.extraField}
          save={props.save}
          onClose={() => setEditing(null)}
          onSaved={(saved, created) => {
            setEditing(null);
            setNotice(`${created ? 'Created' : 'Saved'} ${saved.code}`);
            setReloadKey((k) => k + 1);
          }}
        />
      )}
    </div>
  );
}

interface RecordFormProps<T extends SimpleRecord> {
  record: T | null;
  noun: string;
  permission: Permission;
  extraField?: SimpleRecordsProps<T>['extraField'];
  save: SimpleRecordsProps<T>['save'];
  onClose: () => void;
  onSaved: (record: T, created: boolean) => void;
}

function RecordForm<T extends SimpleRecord>({ record, noun, permission, extraField, save, onClose, onSaved }: RecordFormProps<T>) {
  const [code, setCode] = useState(record?.code ?? '');
  const [name, setName] = useState(record?.name ?? '');
  const [status, setStatus] = useState<ActiveStatus>(record?.status ?? 'ACTIVE');
  const { errors, formError, saving, submit } = useSubmit();

  const send = (event: FormEvent) => {
    event.preventDefault();
    const found: Record<string, string> = {};
    if (!code.trim()) found.code = 'Enter a code';
    else if (!CODE_PATTERN.test(code.trim())) found.code = "Use letters, numbers, '-', '_' or '.'";
    if (!name.trim()) found.name = 'Enter a name';
    void submit(found, async () => onSaved(await save(record?.id ?? null, { code: code.trim(), name: name.trim(), status, version: record?.version }), record === null));
  };

  return (
    <Drawer
      title={record ? `Edit ${record.code}` : `New ${noun}`}
      onClose={onClose}
      permission={permission}
      footer={
        <div className="button-row button-row--end">
          <button type="button" className="button" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" form="record-form" className="button button--primary" disabled={saving}>
            {saving ? 'Saving…' : record ? 'Save changes' : `Create ${noun}`}
          </button>
        </div>
      }
    >
      <form id="record-form" className="form" onSubmit={send} noValidate>
        {formError && (
          <div className="form__error" role="alert">
            {formError}
          </div>
        )}
        <div className="grid grid--2">
          <Field id="record-code" label="Code" error={errors.code}>
            <input
              id="record-code"
              className="input--code"
              value={code}
              maxLength={20}
              autoComplete="off"
              aria-invalid={Boolean(errors.code)}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
            />
          </Field>
          <Field id="record-status" label="Status" error={errors.status}>
            <select id="record-status" value={status} onChange={(e) => setStatus(e.target.value as ActiveStatus)}>
              {ACTIVE_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <Field id="record-name" label="Name" error={errors.name}>
          <input id="record-name" value={name} maxLength={120} autoComplete="off" aria-invalid={Boolean(errors.name)} onChange={(e) => setName(e.target.value)} />
        </Field>
        {extraField && (
          <Field id={extraField.id} label={extraField.label} error={errors.address}>
            <textarea id={extraField.id} rows={3} maxLength={extraField.maxLength} value={extraField.value} onChange={(e) => extraField.onChange(e.target.value)} />
          </Field>
        )}
      </form>
    </Drawer>
  );
}
