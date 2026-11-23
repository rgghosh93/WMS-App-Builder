import { request, type Page } from './http';

export type WorkKind = 'PUTAWAY' | 'REPLENISHMENT' | 'COUNT' | 'PICK' | 'PACK';
export type TaskState = 'WAITING' | 'UNASSIGNED' | 'ASSIGNED' | 'STARTED';

export const WORK_KINDS: { value: WorkKind; label: string }[] = [
  { value: 'PUTAWAY', label: 'Put-away' },
  { value: 'REPLENISHMENT', label: 'Replenishment' },
  { value: 'COUNT', label: 'Count' },
  { value: 'PICK', label: 'Pick' },
  { value: 'PACK', label: 'Pack' },
];

export const TASK_STATES: { value: TaskState; label: string }[] = [
  { value: 'WAITING', label: 'Waiting' },
  { value: 'UNASSIGNED', label: 'Not assigned' },
  { value: 'ASSIGNED', label: 'Assigned' },
  { value: 'STARTED', label: 'Under way' },
];

export interface QueuedTask {
  kind: WorkKind;
  label: string;
  id: string;
  number: string;
  /** What is to be done. */
  what: string;
  /** Where it starts. */
  location: string | null;
  lpn: string | null;
  /** 1 is most urgent, 9 least. */
  priority: number;
  /** What it would be if nobody had changed it. */
  ownPriority: number;
  prioritized: boolean;
  assignedTo: { id: string; name: string } | null;
  /** Who is doing it. */
  worker: string | null;
  started: boolean;
  /** Whether it can be started now. */
  ready: boolean;
  assignable: boolean;
  createdAt: string;
  startedAt: string | null;
  link: string;
}

export interface KindSummary {
  kind: WorkKind;
  label: string;
  waiting: number;
  assigned: number;
  started: number;
  /** When the task waiting longest was created. */
  oldest: string | null;
}

export interface Worker {
  id: string;
  name: string;
  kinds: WorkKind[];
  doing: string[];
  assigned: number;
}

export interface WorkSetting {
  kind: WorkKind;
  label: string;
  /** Null for work that is as urgent as its orders. */
  priority: number | null;
  /** Whether people are offered it as their next task. */
  offered: boolean;
  canBeOffered: boolean;
}

const API = '/api/tasks';
const post = <T>(path: string, body: unknown) => request<T>(`${API}${path}`, { method: 'POST', body: JSON.stringify(body) });

export function listTasks(params: URLSearchParams, signal?: AbortSignal): Promise<Page<QueuedTask>> {
  return request(`${API}?${params}`, { signal });
}

export function getTaskSummary(signal?: AbortSignal): Promise<KindSummary[]> {
  return request(`${API}/summary`, { signal });
}

export function listWorkers(signal?: AbortSignal): Promise<Worker[]> {
  return request(`${API}/people`, { signal });
}

/** Null for the priority it has of itself. */
export function prioritizeTasks(ids: string[], priority: number | null): Promise<{ changed: number }> {
  return post('/priority', { ids, priority });
}

/** Null for nobody in particular. */
export function assignTasks(ids: string[], userId: string | null): Promise<{ changed: number }> {
  return post('/assign', { ids, userId });
}

export function getWorkSettings(signal?: AbortSignal): Promise<WorkSetting[]> {
  return request(`${API}/settings`, { signal });
}

export function saveWorkSettings(settings: { kind: WorkKind; priority: number | null; offered: boolean }[]): Promise<WorkSetting[]> {
  return request(`${API}/settings`, { method: 'PUT', body: JSON.stringify(settings) });
}

// ---------- On a device ----------

export interface NextTask {
  /** Tasks they have started, to be finished first. */
  doing: QueuedTask[];
  /** Null if there is nothing, or something to finish. */
  offer: QueuedTask | null;
  /** How many tasks they could do. */
  waiting: number;
}

/** @param skip tasks not wanted now */
export function getNextTask(skip: string[], signal?: AbortSignal): Promise<NextTask> {
  return request('/api/rf/work/next', { method: 'POST', body: JSON.stringify({ skip }), signal });
}
