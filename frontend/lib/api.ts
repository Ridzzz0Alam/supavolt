/**
 * Single entry point for every call to the .NET API.
 *
 * Two things differ from the original axios setup:
 *  - ASP.NET Core returns RFC 9457 ProblemDetails on failure, so errors have `title`/`detail`
 *    rather than `message`. This normalises that into one Error shape.
 *  - Server components forward the incoming cookie explicitly; the browser sends it itself.
 */

const BASE_URL =
  process.env.NEXT_PUBLIC_API_URL ?? process.env.API_URL ?? 'http://localhost:5000/api';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly detail?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type ProblemDetails = {
  title?: string;
  detail?: string;
  status?: number;
};

export type ApiInit = RequestInit & {
  /** Pass the request cookie header when calling from a server component or server action. */
  cookie?: string;
};

export async function api<T>(path: string, init: ApiInit = {}): Promise<T> {
  const { cookie, headers, ...rest } = init;

  const res = await fetch(`${BASE_URL}${path}`, {
    ...rest,
    credentials: 'include',
    cache: 'no-store',
    headers: {
      'Content-Type': 'application/json',
      ...(cookie ? { Cookie: cookie } : {}),
      ...headers,
    },
  });

  if (!res.ok) {
    let problem: ProblemDetails = {};
    try {
      problem = (await res.json()) as ProblemDetails;
    } catch {
      // A 502 from a proxy has no JSON body; fall through to the status text.
    }

    throw new ApiError(res.status, problem.title ?? res.statusText, problem.detail);
  }

  // 204, and 201 Created from Results.Created, arrive with no body.
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const apiGet = <T>(path: string, init?: ApiInit) => api<T>(path, { ...init, method: 'GET' });

export const apiSend = <T>(method: 'POST' | 'PATCH' | 'PUT' | 'DELETE', path: string, body?: unknown, init?: ApiInit) =>
  api<T>(path, {
    ...init,
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
