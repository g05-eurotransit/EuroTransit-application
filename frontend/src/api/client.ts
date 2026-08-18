import { getAccessToken } from "../auth/tokenHolder";

const API_BASE = (import.meta.env.VITE_API_BASE as string) ?? "/api";

/**
 * Frozen error model from api-design.md: `{ code, message, correlationId }`.
 * Mapped to typed UI outcomes so pages can render clear success/failure states.
 */
export type ApiErrorKind =
  | "unauthenticated" // 401 -> trigger login
  | "forbidden" // 403
  | "not_found" // 404
  | "conflict" // 409 (idempotency / reservation conflict)
  | "business" // 422 (valid shape, invalid business action)
  | "dependency" // 503 (dependency unavailable / circuit open)
  | "network" // fetch threw
  | "unknown";

export class ApiError extends Error {
  constructor(
    readonly kind: ApiErrorKind,
    readonly status: number,
    override readonly message: string,
    readonly code?: string,
    readonly correlationId?: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

function kindForStatus(status: number): ApiErrorKind {
  switch (status) {
    case 401:
      return "unauthenticated";
    case 403:
      return "forbidden";
    case 404:
      return "not_found";
    case 409:
      return "conflict";
    case 422:
      return "business";
    case 503:
      return "dependency";
    default:
      return "unknown";
  }
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  auth?: boolean; // attach Bearer token
  idempotencyKey?: string; // for mutating money-path calls
  correlationId?: string; // ties the money-path trace to this UI action
  signal?: AbortSignal;
}

/**
 * Single fetch wrapper for every API call.
 * - Bearer token from the in-memory holder (never localStorage — 07b slide 18).
 * - Idempotency-Key on mutating calls (frozen contract; a fresh key per checkout attempt).
 * - X-Correlation-Id so the request is greppable across services / in the trace.
 * - Relative same-origin path (no CORS wildcard — 07b slide 12).
 */
export async function apiFetch<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };

  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (opts.auth) {
    const token = getAccessToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;
  }
  if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;
  if (opts.correlationId) headers["X-Correlation-Id"] = opts.correlationId;

  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method: opts.method ?? "GET",
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      signal: opts.signal,
    });
  } catch (e) {
    throw new ApiError("network", 0, e instanceof Error ? e.message : "Network error");
  }

  if (!res.ok) {
    let code: string | undefined;
    let message = res.statusText;
    let correlationId: string | undefined;
    try {
      const problem = (await res.json()) as {
        code?: string;
        message?: string;
        correlationId?: string;
      };
      code = problem.code;
      message = problem.message ?? message;
      correlationId = problem.correlationId;
    } catch {
      // non-JSON error body; keep statusText
    }
    throw new ApiError(kindForStatus(res.status), res.status, message, code, correlationId);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/** Fresh high-entropy id for a checkout attempt / correlation (07b: crypto, not Math.random). */
export function newId(): string {
  return crypto.randomUUID();
}
