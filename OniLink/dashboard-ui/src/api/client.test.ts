import { clearToken, download, getToken, request, setToken } from "./client";

function response(
  body: unknown,
  status = 200,
  headers: Record<string, string> = { "content-type": "application/json" },
) {
  return new Response(typeof body === "string" ? body : JSON.stringify(body), { status, headers });
}

describe("typed API client", () => {
  it("uses URL-encoded bodies, bearer authentication, and no-store", async () => {
    setToken("private-token");
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(response({ ok: true }));
    await request("/api/example", { method: "POST", body: { message: "a&b=c", count: 2 } });
    const init = fetchMock.mock.calls[0]?.[1];
    expect(init?.cache).toBe("no-store");
    expect(new Headers(init?.headers).get("authorization")).toBe("Bearer private-token");
    expect(init?.body).toBeInstanceOf(URLSearchParams);
    expect((init?.body as URLSearchParams).toString()).toBe("message=a%26b%3Dc&count=2");
  });

  it.each([
    [403, "forbidden"],
    [409, "conflict"],
    [429, "rate-limited"],
    [503, "server"],
  ] as const)("classifies %s responses as %s", async (status, kind) => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(response({ error: "Rejected" }, status));
    await expect(request("/api/example")).rejects.toMatchObject({
      status,
      kind,
      message: "Rejected",
    });
  });

  it("invalidates the local token on an authenticated 401", async () => {
    setToken("expired");
    vi.spyOn(globalThis, "fetch").mockResolvedValue(response({ error: "Unauthorized" }, 401));
    await expect(request("/api/private")).rejects.toMatchObject({ kind: "unauthorized" });
    expect(getToken()).toBe("");
  });

  it("downloads binary responses and revokes the object URL", async () => {
    vi.useFakeTimers();
    setToken("valid");
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      response("zip", 200, {
        "content-type": "application/zip",
        "content-disposition": "attachment; filename=support.zip",
      }),
    );
    const create = vi.fn(() => "blob:test");
    const revoke = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: create });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revoke });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    await download("/api/support-bundle", "fallback.zip");
    expect(create).toHaveBeenCalledOnce();
    vi.runAllTimers();
    expect(revoke).toHaveBeenCalledWith("blob:test");
    clearToken(false);
  });
});
