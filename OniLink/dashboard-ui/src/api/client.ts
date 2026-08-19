const TOKEN_KEY = "onilink_dashboard_token";
let sessionGeneration = 0;

export type ApiErrorKind =
  "unauthorized" | "forbidden" | "conflict" | "rate-limited" | "server" | "network" | "request";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly kind: ApiErrorKind,
    readonly data?: unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export function getToken(): string {
  return sessionStorage.getItem(TOKEN_KEY) ?? "";
}

export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token);
  sessionGeneration += 1;
}

export function clearToken(notify = true): void {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionGeneration += 1;
  if (notify) window.dispatchEvent(new Event("onilink:session-invalid"));
}

function errorKind(status: number): ApiErrorKind {
  if (status === 401) return "unauthorized";
  if (status === 403) return "forbidden";
  if (status === 409) return "conflict";
  if (status === 429) return "rate-limited";
  if (status >= 500) return "server";
  return "request";
}

async function parsePayload(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return response.json().catch(() => null);
  }
  return response.text().catch(() => "");
}

function payloadMessage(payload: unknown, fallback: string): string {
  if (typeof payload === "string" && payload.trim()) return payload.trim();
  if (payload && typeof payload === "object") {
    const value = payload as Record<string, unknown>;
    if (typeof value.error === "string") return value.error;
    if (typeof value.message === "string") return value.message;
  }
  return fallback;
}

export interface RequestOptions {
  method?: "GET" | "POST" | "DELETE";
  body?: Record<string, string | number | boolean | null | undefined>;
  signal?: AbortSignal;
  authenticated?: boolean;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const generation = sessionGeneration;
  const headers = new Headers();
  const token = getToken();
  if (options.authenticated !== false && token) headers.set("Authorization", `Bearer ${token}`);
  let body: URLSearchParams | undefined;
  if (options.body) {
    body = new URLSearchParams();
    for (const [key, value] of Object.entries(options.body))
      body.set(key, value == null ? "" : String(value));
    headers.set("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? "GET",
      headers,
      body,
      cache: "no-store",
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ApiError(
      "Unable to reach OniLink. Check the connection and try again.",
      0,
      "network",
    );
  }
  if (generation !== sessionGeneration) throw new DOMException("Session changed", "AbortError");
  const payload = await parsePayload(response);
  if (!response.ok) {
    if (response.status === 401 && options.authenticated !== false) clearToken();
    throw new ApiError(
      payloadMessage(payload, `Request failed (${response.status})`),
      response.status,
      errorKind(response.status),
      payload,
    );
  }
  return payload as T;
}

function dispositionFilename(response: Response, fallback: string): string {
  const disposition = response.headers.get("content-disposition") ?? "";
  const match = /filename="?([^";]+)"?/i.exec(disposition);
  return match?.[1]?.replace(/[\\/\0]/g, "-") || fallback;
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

export async function download(
  path: string,
  fallbackName: string,
  signal?: AbortSignal,
): Promise<void> {
  const generation = sessionGeneration;
  const response = await fetch(path, {
    headers: { Authorization: `Bearer ${getToken()}` },
    cache: "no-store",
    signal,
  });
  if (generation !== sessionGeneration) throw new DOMException("Session changed", "AbortError");
  if (!response.ok) {
    const payload = await parsePayload(response);
    if (response.status === 401) clearToken();
    throw new ApiError(
      payloadMessage(payload, "Download failed"),
      response.status,
      errorKind(response.status),
      payload,
    );
  }
  saveBlob(await response.blob(), dispositionFilename(response, fallbackName));
}

export function downloadBase64(
  filename: string,
  encoded: string,
  contentType = "application/octet-stream",
): void {
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  saveBlob(new Blob([bytes], { type: contentType }), filename);
}

export function downloadText(filename: string, content: string): void {
  saveBlob(new Blob([content], { type: "text/plain;charset=UTF-8" }), filename);
}
