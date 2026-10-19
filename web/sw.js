'use strict';
/**
 * Keeps the whole web app on the phone, so it opens offline. build.sh fills in FILES (path to the first 8 hex digits
 * of its SHA-1) and VERSION (the same of FILES). A file is stored under "path?hash", so a new build downloads only the
 * files that changed and copies the rest from the old cache. One build is kept, about 23 MB of Cache Storage; the old
 * one is deleted when the new one takes over. dexsearch.apk and version are not listed and always come from the network.
 */
const VERSION = '%VERSION%';
const FILES = /*FILES*/{};
const CACHE = 'dex-' + VERSION;
const BASE = new URL('./', location.href).pathname;

self.addEventListener('install', e => e.waitUntil((async () => {
  const cache = await caches.open(CACHE);
  const todo = Object.entries(FILES);
  // Eight downloads at a time: thousands at once fail on a weak connection, one at a time takes minutes.
  const worker = async () => {
    for (let job = todo.pop(); job; job = todo.pop()) {
      const key = job[0] + '?' + job[1];
      let r = await caches.match(key);
      if (!r) {
        r = await fetch(key);
        if (!r.ok) throw new Error(key + ': ' + r.status);
      }
      await cache.put(key, r);
    }
  };
  await Promise.all(Array.from({ length: 8 }, worker));
})()));

self.addEventListener('activate', e => e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))));

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin || !url.pathname.startsWith(BASE)) return;
  const path = decodeURIComponent(url.pathname.slice(BASE.length)) || 'index.html';
  const hash = FILES[path];
  if (hash) e.respondWith(caches.match(path + '?' + hash, { cacheName: CACHE }).then(r => r ?? fetch(e.request)));
});

// The home screen's "Update available" asks the waiting build to take over.
self.addEventListener('message', e => { if (e.data === 'skip') self.skipWaiting(); });
