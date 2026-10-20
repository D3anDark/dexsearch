'use strict';
/**
 * Keeps the whole web app on the phone, so it opens offline. build.sh fills in FILES (path to the first 8 hex digits
 * of its SHA-1) and VERSION (the same of FILES). A file is stored under "path?hash", so a new build downloads only the
 * files that changed and copies the rest from the old cache. One build is kept, about 23 MB of Cache Storage plus the
 * LATER files in use; the old one is deleted when the new one takes over. dexsearch.apk and version are not listed and
 * always come from the network.
 */
const VERSION = '%VERSION%';
const FILES = /*FILES*/{};
const CACHE = 'dex-' + VERSION;
const BASE = new URL('./', location.href).pathname;
/**
 * Official art, pixel and shiny pictures (about 60 MB): not downloaded on install. The style picked in Settings is
 * fetched whole when the page asks (5 or 17 MB), a shiny picture when it is shown.
 */
const LATER = /^assets\/(art|pixel|shiny[^/]*)\//;

/**
 * Stores each [path, hash] of `todo` in this build's cache, copied from an older build's when unchanged. A LATER file
 * found in no cache is downloaded only when `later` is set; a failed download fails the call.
 */
async function keep(todo, later) {
  const cache = await caches.open(CACHE);
  // Eight downloads at a time: thousands at once fail on a weak connection, one at a time takes minutes.
  const worker = async () => {
    for (let job = todo.pop(); job; job = todo.pop()) {
      const key = job[0] + '?' + job[1];
      if (await cache.match(key)) continue;
      let r = await caches.match(key);
      if (!r) {
        if (!later && LATER.test(job[0])) continue;
        r = await fetch(key);
        if (!r.ok) throw new Error(key + ': ' + r.status);
      }
      await cache.put(key, r);
    }
  };
  await Promise.all(Array.from({ length: 8 }, worker));
}

self.addEventListener('install', e => e.waitUntil(keep(Object.entries(FILES), false)));

self.addEventListener('activate', e => e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))));

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin || !url.pathname.startsWith(BASE)) return;
  const path = decodeURIComponent(url.pathname.slice(BASE.length)) || 'index.html';
  const hash = FILES[path];
  if (!hash) return;
  const key = path + '?' + hash;
  e.respondWith(caches.match(key, { cacheName: CACHE }).then(r => r ?? fetch(e.request).then(r => {
    if (r.ok && LATER.test(path)) {
      const copy = r.clone();
      e.waitUntil(caches.open(CACHE).then(cache => cache.put(key, copy)));
    }
    return r;
  })));
});

self.addEventListener('message', e => {
  // The home screen's "Update available" asks the waiting build to take over.
  if (e.data === 'skip') self.skipWaiting();
  // Settings picked an artwork style, or the app started with one: keep all of it.
  else if (typeof e.data?.art === 'string') {
    const dir = 'assets/' + e.data.art + '/';
    e.waitUntil(keep(Object.entries(FILES).filter(([p]) => p.startsWith(dir)), true));
  }
});
