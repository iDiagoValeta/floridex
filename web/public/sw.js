// Offline support: every same-origin GET (app shell, ONNX Runtime wasm,
// flower cards, sprites, photos, the model's JSON files) is served from cache
// once it has been fetched; the build precaches the app's own files.
// The build replaces VERSION with a hash of the files' contents, so every new
// build replaces this app's previous cache, and only that one.
const VERSION = "floridex-dev";
// This app's caches. transformers.js keeps the model in its own cache
// ("transformers-cache"), which is not ours to delete.
const PROPIAS = ["floridex-"];

self.addEventListener("install", (e) => {
  // precache.json is written by the build (vite.config.ts) with every app file.
  e.waitUntil(
    fetch("./precache.json")
      .then((r) => (r.ok ? r.json() : []))
      .catch(() => [])
      .then((ficheros) => caches.open(VERSION).then((c) => c.addAll(["./", ...ficheros.map((f) => `./${f}`)]))),
  );
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    // No clients.claim(): taking over a page mid-load made Safari abort the
    // model download. The next visit is controlled and works offline.
    caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== VERSION && PROPIAS.some((p) => k.startsWith(p))).map((k) => caches.delete(k)))),
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== self.location.origin) return;
  // transformers.js keeps the model weights in its own Cache Storage and reads
  // them from there offline; caching them here too broke the 25 MB streamed
  // download. The model's small JSON files are cached here: the page reads
  // config.json itself for the list of flowers.
  if (url.pathname.includes("/models/") && url.pathname.endsWith(".onnx")) return;
  // Pages: network first so updates arrive, cache when offline.
  if (e.request.mode === "navigate") {
    e.respondWith(
      fetch(e.request)
        .then((res) => {
          const copy = res.clone();
          caches.open(VERSION).then((c) => c.put(e.request, copy));
          return res;
        })
        .catch(() => caches.match(e.request, { ignoreSearch: true, ignoreVary: true }).then((r) => r ?? caches.match("./index.html", { ignoreVary: true }))),
    );
    return;
  }
  e.respondWith(
    caches.open(VERSION).then(async (cache) => {
      // ignoreVary: precached copies were fetched without the Origin header a
      // module script request carries; with "Vary: Origin" they never matched.
      const hit = await cache.match(e.request, { ignoreSearch: true, ignoreVary: true });
      if (hit) return hit;
      const res = await fetch(e.request);
      // Only cache real files: a 404 must stay a 404 (transformers.js probes optional files).
      if (res.ok) cache.put(e.request, res.clone());
      return res;
    }),
  );
});
