const SHELL_CACHE = "onilink-shell-v0.3.0-beta.1";
const SHELL = [
  "/",
  "/offline.html",
  "/offline.css",
  "/manifest.webmanifest",
  "/icons/onilink.svg",
  "/icons/onilink-180.png",
  "/icons/onilink-192.png",
  "/icons/onilink-512.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((key) => key !== SHELL_CACHE).map((key) => caches.delete(key))),
      ),
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (
    request.method !== "GET" ||
    url.origin !== self.location.origin ||
    url.pathname.startsWith("/api/") ||
    url.pathname === "/metrics"
  )
    return;
  event.respondWith(
    fetch(request).catch(async () => {
      const cached = await caches.match(request);
      return cached || caches.match("/offline.html");
    }),
  );
});

self.addEventListener("push", (event) => {
  let payload = { summary: "OniLink event", route: "/#/notifications" };
  try {
    payload = { ...payload, ...event.data.json() };
  } catch {
    /* Redacted fallback only. */
  }
  event.waitUntil(
    self.registration.showNotification("OniLink", {
      body: String(payload.summary).slice(0, 160),
      icon: "/icons/onilink-192.png",
      data: { route: String(payload.route).startsWith("/#/") ? payload.route : "/#/notifications" },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(self.clients.openWindow(event.notification.data?.route || "/#/notifications"));
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "CLEAR_PRIVATE_CACHES") {
    event.waitUntil(
      caches
        .keys()
        .then((keys) =>
          Promise.all(keys.filter((key) => key !== SHELL_CACHE).map((key) => caches.delete(key))),
        ),
    );
  }
});
