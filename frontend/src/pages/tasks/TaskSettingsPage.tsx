import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../../api/http';
import { getWorkSettings, saveWorkSettings, type WorkSetting } from '../../api/tasks';
import { useCan } from '../../session/SessionContext';

const NOTES: Record<string, string> = {
  REPLENISHMENT: 'Two higher when orders are waiting for it, one higher when the pick location is empty',
  COUNT: 'Two higher when it has to be counted again',
  PICK: 'As urgent as the most urgent of its orders',
  PACK: 'As urgent as the most urgent of its orders; done by whoever works at the station',
};

/** How urgent each kind of work is, and whether people are offered it as their next task. */
export function TaskSettingsPage() {
  const may = useCan('TASKS');
  const [settings, setSettings] = useState<WorkSetting[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    getWorkSettings(controller.signal)
      .then(setSettings)
      .catch((e) => {
        if (!controller.signal.aborted) setError(errorMessage(e));
      });
    return () => controller.abort();
  }, [attempt]);

  useEffect(() => {
    if (!notice) return;
    const handle = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(handle);
  }, [notice]);

  const change = (kind: string, changes: Partial<WorkSetting>) =>
    setSettings((all) => all!.map((s) => (s.kind === kind ? { ...s, ...changes } : s)));

  const save = async (event: FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      setSettings(await saveWorkSettings(settings!.map(({ kind, priority, offered }) => ({ kind, priority, offered }))));
      setNotice('Saved');
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to="/">Home</Link>
        <span aria-hidden="true">/</span>
        <span>Task Management</span>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Task Settings</span>
      </nav>
      <header className="page__header">
        <div>
          <h1>Task Settings</h1>
          <p className="muted">
            How urgent each kind of work is, from 1 to 9, unless the <Link to="/tasks/queue">queue</Link> says otherwise for a task. Of equally urgent tasks, people
            are offered the one nearest to where they last handled stock.
          </p>
        </div>
      </header>
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
      {error && (
        <div className="notice" role="alert">
          <p>{error}</p>
          {!settings && (
            <button
              type="button"
              className="button"
              onClick={() => {
                setError(null);
                setAttempt((n) => n + 1);
              }}
            >
              Try again
            </button>
          )}
        </div>
      )}
      {!settings && !error && <p className="muted">Loading…</p>}
      {settings && (
        <form onSubmit={save}>
          <div className="table-wrap">
            <table className="table" aria-label="Kinds of work">
              <thead>
                <tr>
                  <th scope="col">Kind of work</th>
                  <th scope="col">Priority</th>
                  <th scope="col">Offered as the next task</th>
                </tr>
              </thead>
              <tbody>
                {settings.map((s) => (
                  <tr key={s.kind}>
                    <th scope="row">
                      {s.label}
                      {NOTES[s.kind] && <span className="muted cell__sub">{NOTES[s.kind]}</span>}
                    </th>
                    <td>
                      {s.priority === null ? (
                        <span className="muted">Its orders'</span>
                      ) : (
                        <select
                          aria-label={`Priority of ${s.label}`}
                          value={s.priority}
                          disabled={!may}
                          onChange={(e) => change(s.kind, { priority: Number(e.target.value) })}
                        >
                          {[1, 2, 3, 4, 5, 6, 7, 8, 9].map((p) => (
                            <option key={p} value={p}>
                              {p}
                            </option>
                          ))}
                        </select>
                      )}
                    </td>
                    <td>
                      {s.canBeOffered ? (
                        <input
                          type="checkbox"
                          aria-label={`Offer ${s.label}`}
                          checked={s.offered}
                          disabled={!may}
                          onChange={(e) => change(s.kind, { offered: e.target.checked })}
                        />
                      ) : (
                        <span className="muted">No</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {may && (
            <p>
              <button type="submit" className="button button--primary" disabled={saving}>
                {saving ? 'Saving…' : 'Save'}
              </button>
            </p>
          )}
        </form>
      )}
    </div>
  );
}
