// Browser-side helper: calls the Next.js /bff proxy, never the backend directly.
export class ClientError extends Error {
  constructor(public status: number, message: string, public debugId?: string) {
    super(message);
  }
}

export async function bff<T>(method: "POST" | "PATCH", path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`/bff${path}`, {
      method,
      headers: { "Content-Type": "application/json", ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ClientError(0, "Network error. Check your connection and try again.");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg =
      res.status === 429
        ? "Rate limit reached for the public demo. Please wait a while before trying again."
        : (data.detail ?? data.title ?? `Request failed (${res.status})`);
    throw new ClientError(res.status, msg, data.paypalDebugId);
  }
  return data as T;
}
