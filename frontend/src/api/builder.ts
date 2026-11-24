import { request } from './http';

export type ChangeStatus = 'DRAFTING' | 'ACCEPTED' | 'IN_REVIEW' | 'RELEASED' | 'DISCARDED';

export interface BuilderStatus {
  enabled: boolean;
  model: string;
  /** The agent may run the tests. */
  checks: boolean;
  /** Change requests can be tried in a preview here. */
  previews: boolean;
  /** The branch accepted changes go to. */
  acceptBranch: string;
}

/** A change request's own copy of the WMS, started to try it. */
export interface Preview {
  state: 'STARTING' | 'RUNNING' | 'FAILED';
  url: string | null;
  /** It shows the change as it is now. */
  upToDate: boolean;
  /** What it is doing while starting. */
  step: string | null;
  /** Why it failed. */
  problem: string | null;
  since: string;
  /** The administrator made for whoever started it, in its own throwaway database. */
  signIn: { userCode: string | null; password: string | null; note: string | null } | null;
}

/** A question the agent asks before building. */
export interface Question {
  question: string;
  /** Choices to pick from; none for a question answered in words. */
  options: { label: string; description: string | null }[];
  /** More than one choice can be picked. */
  multiple: boolean;
  /** The label of the choice the agent recommends. */
  recommended: string | null;
}

/** Questions the agent is waiting for answers to. */
export interface PendingQuestions {
  callId: string;
  intro: string | null;
  questions: Question[];
}

/** An answer to one question: the choices picked, and the person's own words. */
export interface Answer {
  question: number;
  chosen: string[];
  other: string | null;
}

export interface Accepted {
  at: string;
  by: string;
  into: string;
  /** Nothing has changed in it since. */
  upToDate: boolean;
}

/** A change someone asked the App Builder for. */
export interface ChangeSummary {
  id: string;
  number: string;
  title: string;
  status: ChangeStatus;
  /** The agent is working on it now. */
  working: boolean;
  requestedBy: string;
  createdAt: string;
  updatedAt: string;
}

export type Block =
  | { type: 'text'; text: string }
  | { type: 'tool_call'; id: string; name: string; input: Record<string, unknown> }
  | { type: 'tool_result'; callId: string; content: string; error: boolean };

/** A turn of the conversation: what the person said, what the agent said and did, or what came of what it did. */
export interface Entry {
  seq: number;
  role: 'PERSON' | 'AGENT' | 'RESULTS';
  blocks: Block[];
  model: string | null;
  at: string;
}

export interface ChangeDetail {
  change: ChangeSummary;
  entries: Entry[];
  /** What the change touches so far: "M path" changed, "A path" added, "D path" deleted. */
  files: string[];
  branch: string;
  /** Why the agent's last run didn't finish, if it didn't. */
  lastProblem: string | null;
  inputTokens: number;
  outputTokens: number;
  accepted: Accepted | null;
  preview: Preview | null;
  /** Questions the agent is waiting for answers to. */
  questions: PendingQuestions | null;
  /** Set when it is closed. */
  discarded: { at: string; by: string; reason: string | null } | null;
  /**
   * The person may see how it is built. Without it, the server leaves out code, files, test output, branch and model use,
   * and the conversation is in business terms only.
   */
  developer: boolean;
}

/** Something a change request you asked for needs from you. */
export interface BuilderNotification {
  id: string;
  number: string;
  title: string;
  kind: 'FINISHED' | 'QUESTIONS' | 'PROBLEM';
  message: string;
  at: string;
}

export function builderNotifications(signal?: AbortSignal): Promise<BuilderNotification[]> {
  return request('/api/builder/notifications', { signal });
}

/** You have looked at it; it no longer needs you. */
export function markSeen(id: string): Promise<void> {
  return request(`/api/builder/changes/${id}/seen`, { method: 'POST' });
}

export function builderStatus(signal?: AbortSignal): Promise<BuilderStatus> {
  return request('/api/builder/status', { signal });
}

export function listChanges(signal?: AbortSignal): Promise<ChangeSummary[]> {
  return request('/api/builder/changes', { signal });
}

export function getChange(id: string, signal?: AbortSignal): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}`, { signal });
}

export function startChange(text: string): Promise<ChangeDetail> {
  return request('/api/builder/changes', { method: 'POST', body: JSON.stringify({ text }) });
}

export function sayToAgent(id: string, text: string): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}/messages`, { method: 'POST', body: JSON.stringify({ text }) });
}

export function stopAgent(id: string): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}/stop`, { method: 'POST' });
}

export function answerQuestions(id: string, callId: string, answers: Answer[]): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}/answers`, { method: 'POST', body: JSON.stringify({ callId, answers }) });
}

export function discardChange(id: string, reason: string | null): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}/discard`, { method: 'POST', body: JSON.stringify({ reason }) });
}

export function reopenChange(id: string): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}/reopen`, { method: 'POST' });
}

export function acceptChange(id: string): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}/accept`, { method: 'POST' });
}

export function startPreview(id: string): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}/preview`, { method: 'POST' });
}

export function stopPreview(id: string): Promise<ChangeDetail> {
  return request(`/api/builder/changes/${id}/preview`, { method: 'DELETE' });
}
