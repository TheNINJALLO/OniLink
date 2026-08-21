import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const publicFile = (name: string) => resolve(process.cwd(), "public", name);

describe("operations PWA", () => {
  it("has an installable manifest and iOS/Chromium raster icons", () => {
    const manifest = JSON.parse(readFileSync(publicFile("manifest.webmanifest"), "utf8")) as {
      display: string;
      start_url: string;
      icons: Array<{ src: string; sizes: string }>;
    };
    expect(manifest.display).toBe("standalone");
    expect(manifest.start_url).toBe("/#/overview");
    expect(manifest.icons).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ src: "/icons/onilink-192.png", sizes: "192x192" }),
        expect.objectContaining({ src: "/icons/onilink-512.png", sizes: "512x512" }),
      ]),
    );
    expect(existsSync(publicFile("icons/onilink-180.png"))).toBe(true);
  });

  it("never caches authenticated API or metrics responses", () => {
    const worker = readFileSync(publicFile("sw.js"), "utf8");
    expect(worker).toContain('url.pathname.startsWith("/api/")');
    expect(worker).toContain('url.pathname === "/metrics"');
    expect(worker).toContain("CLEAR_PRIVATE_CACHES");
    expect(worker).toContain('self.addEventListener("push"');
    expect(worker).not.toContain("localStorage");
  });
});
