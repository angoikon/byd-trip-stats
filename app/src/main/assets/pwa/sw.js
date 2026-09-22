// Replaced at build time by the syncPwa Gradle task with the app's versionName, so that every
// release ships a worker whose bytes differ, which is what makes the browser install it and drop
// the previous cache. Left alone (the GitHub Pages copy) it is simply a stable name.
const VERSION = '2.17.0-beta29';
const CACHE = 'byd-pwa-' + VERSION;

// Kept only as an offline fallback for the shell — see the fetch handler for why that is a
// courtesy rather than a feature.
const SHELL = ['./index.html', './manifest.json'];

// Paths the shell is reached by: start_url is ./index.html, and a bare visit lands on /.
const SHELL_PATHS = ['/', '/index.html', '/manifest.json'];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function isShell(url) {
  return url.origin === self.location.origin && SHELL_PATHS.includes(url.pathname);
}

/** fetch that stops waiting after ms, so an unreachable car can't hang the launch. */
function fetchWithTimeout(request, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), ms);
    fetch(request).then(
      res => { clearTimeout(timer); resolve(res); },
      err => { clearTimeout(timer); reject(err); }
    );
  });
}

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);

  // Cache-first for CartoDB dark map tiles — makes previously viewed maps work offline
  if (url.hostname.endsWith('.basemaps.cartocdn.com') || url.hostname === 'basemaps.cartocdn.com') {
    e.respondWith(
      caches.match(e.request).then(hit => hit || fetch(e.request).then(res => {
        if (res.ok) caches.open(CACHE).then(c => c.put(e.request, res.clone()));
        return res;
      }))
    );
    return;
  }

  // Network-first for CDN libs (sql.js, Chart.js, Leaflet) — cache on success
  if (['cdnjs.cloudflare.com', 'cdn.jsdelivr.net', 'unpkg.com'].some(h => url.hostname === h)) {
    e.respondWith(
      fetch(e.request)
        .then(res => {
          if (res.ok) caches.open(CACHE).then(c => c.put(e.request, res.clone()));
          return res;
        })
        .catch(() => caches.match(e.request))
    );
    return;
  }

  // Network-first for the app shell.
  //
  // Cache-first here pinned a caller to the first index.html it ever saw: the cache name never
  // changed, a worker is only reinstalled when its own bytes change, and start_url points straight
  // at the cached file — so an app update could never reach an installed PWA. It went unnoticed
  // for as long as it did only because the worker never registered at all over plain HTTP, and
  // HTTPS over Tailscale is what first gave it a secure origin to register on.
  //
  // The cache fallback keeps the app opening when the car is unreachable, but that is a courtesy,
  // not a feature: every screen needs the car's API, so a shell without it can do very little.
  if (isShell(url)) {
    e.respondWith(
      fetchWithTimeout(e.request, 3000)
        .then(res => {
          if (res.ok) {
            const copy = res.clone();
            caches.open(CACHE).then(c => c.put(e.request, copy));
          }
          return res;
        })
        .catch(() => caches.match(e.request).then(hit => hit || Response.error()))
    );
    return;
  }

  // Everything else same-origin — /api/*, file downloads, the log viewer — is deliberately not
  // touched. It is live data, and a stale copy of it would be worse than an honest error.
});
