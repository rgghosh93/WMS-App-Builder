/** An error response from the WMS API, with optional messages per request field. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** One page of results from a list endpoint. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

interface ProblemBody {
  detail?: string;
  title?: string;
  errors?: Record<string, string>;
}

/** Fired when the server refuses a desktop request because of who is, or isn't, signed in. */
export const SESSION_STALE = 'wms:session-stale';

export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  headers.set('Accept', 'application/json');

  let response: Response;
  try {
    response = await fetch(url, { ...init, headers });
  } catch (error) {
    // Browsers only say "Failed to fetch" when nothing answers.
    if (init.signal?.aborted) throw error;
    throw new Error("Can't reach the WMS. Check that it is running and that you are connected, then try again.");
  }
  if (!response.ok) {
    let problem: ProblemBody = {};
    try {
      problem = await response.json();
    } catch {
      // not JSON; fall back to the status line
    }
    // The session may have ended or its permissions changed: let the app look again.
    if ((response.status === 401 || response.status === 403) && !url.startsWith('/api/rf/') && !url.startsWith('/api/session')) {
      window.dispatchEvent(new Event(SESSION_STALE));
    }
    throw new ApiError(
      response.status,
      problem.detail ?? problem.title ?? `Request failed (HTTP ${response.status})`,
      problem.errors ?? {},
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json();
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong';
}
