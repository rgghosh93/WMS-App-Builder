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

export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  headers.set('Accept', 'application/json');

  const response = await fetch(url, { ...init, headers });
  if (!response.ok) {
    let problem: ProblemBody = {};
    try {
      problem = await response.json();
    } catch {
      // not JSON; fall back to the status line
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
