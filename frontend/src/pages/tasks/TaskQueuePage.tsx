import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { RefreshCw } from 'lucide-react';
import { errorMessage } from '../../api/http';
import {
  TASK_STATES,
  WORK_KINDS,
  assignTasks,
  getTaskSummary,
  listTasks,
  listWorkers,
  prioritizeTasks,
  type KindSummary,
  type QueuedTask,
  type Worker,
} from '../../api/tasks';
import { FilterSelect, ListPage } from '../../components/ListPage';
import { useList } from '../../components/useList';
import { useCan } from '../../session/SessionContext';
import { formatDateTime } from '../outbound/OrdersPage';

const PRIORITIES = [1, 2, 3, 4, 5, 6, 7, 8, 9];

/** All work that isn't finished, most urgent first: how urgent it is and who is to do it can be changed here. */
export function TaskQueuePage() {
  const list = useList<QueuedTask>(listTasks);
  const may = useCan('TASKS');
  const [summary, setSummary] = useState<KindSummary[]>([]);
  const [workers, setWorkers] = useState<Worker[]>([]);
  const [selected, setSelected] = useState<Map<string, QueuedTask>>(new Map());
  const [priority, setPriority] = useState('');
  const [person, setPerson] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([getTaskSummary(controller.signal), listWorkers(controller.signal)])
      .then(([kinds, people]) => {
        setSummary(kinds);
        setWorkers(people);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setProblem(errorMessage(e));
      });
    return () => controller.abort();
  }, [refresh]);

  const reload = () => {
    setRefresh((n) => n + 1);
    list.reload();
  };

  const toggle = (tasks: QueuedTask[], on: boolean) =>
    setSelected((current) => {
      const next = new Map(current);
      tasks.forEach((t) => (on ? next.set(t.id, t) : next.delete(t.id)));
      return next;
    });

  const act = async (action: (ids: string[]) => Promise<{ changed: number }>, done: (count: string) => string) => {
    setBusy(true);
    setProblem(null);
    try {
      const { changed } = await action([...selected.keys()]);
      setNotice(done(`${changed} task${changed === 1 ? '' : 's'}`));
      setSelected(new Map());
      setPriority('');
      setPerson('');
    } catch (e) {
      setProblem(errorMessage(e));
    } finally {
      setBusy(false);
      reload();
    }
  };

  const chosen = [...selected.values()];
  // Only those who may do everything that is selected.
  const able = workers.filter((p) => chosen.every((t) => p.kinds.includes(t.kind)));
  const kind = list.params.get('kind') ?? '';

  return (
    <ListPage
      module="Task Management"
      title="Task Queue"
      intro={
        <>
          All work that isn't finished, most urgent first. Priority 1 is the most urgent. Each kind of work is handed out in
          this order, and <Link to="/rf/next">Next task</Link> on a mobile device goes through all of it.
        </>
      }
      actions={
        <button type="button" className="button" onClick={reload}>
          <RefreshCw size={16} aria-hidden="true" />
          Refresh
        </button>
      }
      searchLabel="Search tasks"
      searchPlaceholder="Search task, what, location, LPN or person…"
      filters={
        <>
          <FilterSelect label="Kind" value={kind} options={WORK_KINDS} onChange={(v) => list.update({ kind: v })} />
          <FilterSelect label="State" value={list.params.get('state') ?? ''} options={TASK_STATES} onChange={(state) => list.update({ state })} />
          <FilterSelect
            label="Person"
            value={list.params.get('person') ?? ''}
            options={workers.map((p) => ({ value: p.id, label: p.name }))}
            onChange={(v) => list.update({ person: v })}
          />
        </>
      }
      noun={['task', 'tasks']}
      list={list}
      notice={notice}
      onNoticeGone={useCallback(() => setNotice(null), [])}
      empty={list.params.toString() ? 'No tasks match your search.' : 'There is nothing to do.'}
      before={
        <>
          {summary.length > 0 && (
            <dl className="facts" aria-label="Work by kind">
              {summary.map((k) => (
                <div key={k.kind}>
                  <dt>{k.label}</dt>
                  <dd>
                    {k.waiting.toLocaleString()} waiting
                    <span className="muted cell__sub">
                      {[k.assigned > 0 && `${k.assigned} assigned`, `${k.started} under way`].filter(Boolean).join(' · ')}
                    </span>
                  </dd>
                </div>
              ))}
            </dl>
          )}
          {problem && (
            <div className="notice" role="alert">
              <p>{problem}</p>
            </div>
          )}
          {may && selected.size > 0 && (
            <div className="toolbar" role="group" aria-label="Selected tasks">
              <strong>{selected.size} selected</strong>
              <label className="select-inline">
                <span>Priority</span>
                <select value={priority} onChange={(e) => setPriority(e.target.value)}>
                  <option value="">Choose…</option>
                  {PRIORITIES.map((p) => (
                    <option key={p} value={p}>
                      {p}
                    </option>
                  ))}
                  <option value="own">Its own</option>
                </select>
              </label>
              <button
                type="button"
                className="button"
                disabled={busy || !priority}
                onClick={() => void act((ids) => prioritizeTasks(ids, priority === 'own' ? null : Number(priority)), (n) => `Changed the priority of ${n}`)}
              >
                Set priority
              </button>
              {chosen.every((t) => t.assignable && !t.started) && (
                <>
                  <label className="select-inline">
                    <span>Assign to</span>
                    <select value={person} onChange={(e) => setPerson(e.target.value)}>
                      <option value="">Choose…</option>
                      <option value="none">Nobody</option>
                      {able.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name}
                          {busyWith(p)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <button
                    type="button"
                    className="button"
                    disabled={busy || !person}
                    onClick={() =>
                      void act(
                        (ids) => assignTasks(ids, person === 'none' ? null : person),
                        (n) => (person === 'none' ? `${n} for anyone again` : `Assigned ${n} to ${workers.find((p) => p.id === person)?.name}`),
                      )
                    }
                  >
                    Assign
                  </button>
                </>
              )}
              <button type="button" className="link-button" onClick={() => setSelected(new Map())}>
                Clear selection
              </button>
            </div>
          )}
        </>
      }
    >
      {(rows) => (
        <table className="table" aria-label="Task queue">
          <thead>
            <tr>
              {may && (
                <th scope="col" className="table__select">
                  <input
                    type="checkbox"
                    aria-label="Select all on this page"
                    checked={rows.every((t) => selected.has(t.id))}
                    onChange={(e) => toggle(rows, e.target.checked)}
                  />
                </th>
              )}
              <th scope="col" className="num">
                Priority
              </th>
              <th scope="col">Task</th>
              <th scope="col">What</th>
              <th scope="col">Where</th>
              <th scope="col">State</th>
              <th scope="col">Who</th>
              <th scope="col">Since</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.id}>
                {may && (
                  <td className="table__select">
                    <input type="checkbox" aria-label={`Select ${t.number}`} checked={selected.has(t.id)} onChange={(e) => toggle([t], e.target.checked)} />
                  </td>
                )}
                <td className="num">
                  <strong>{t.priority}</strong>
                  {t.prioritized && <span className="muted cell__sub">was {t.ownPriority}</span>}
                </td>
                <th scope="row">
                  <Link className="code" to={t.link}>
                    {t.number}
                  </Link>
                  <span className="muted cell__sub">{t.label}</span>
                </th>
                <td>{t.what}</td>
                <td>
                  {t.location ? <span className="code">{t.location}</span> : <span className="muted">—</span>}
                  {t.lpn && <span className="muted cell__sub">{t.lpn}</span>}
                </td>
                <td>{state(t)}</td>
                <td>{t.worker ?? t.assignedTo?.name ?? <span className="muted">Anyone</span>}</td>
                <td className="muted">{formatDateTime(t.startedAt ?? t.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </ListPage>
  );
}

function state(t: QueuedTask) {
  if (t.started) return <span className="badge badge--task-in-progress">Under way</span>;
  if (!t.ready) return <span className="badge badge--task-short">Can't start now</span>;
  return <span className="badge badge--task-open">{t.assignedTo ? 'Assigned' : 'Waiting'}</span>;
}

/** What someone has on already. */
function busyWith(p: Worker): string {
  const parts = [p.doing.length > 0 && `doing ${p.doing.join(', ')}`, p.assigned > 0 && `${p.assigned} assigned`].filter(Boolean);
  return parts.length > 0 ? ` (${parts.join(', ')})` : '';
}
