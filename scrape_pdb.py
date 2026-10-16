"""Downloads pokemondb.net pages into pdb_cache/ (gzipped HTML) for gen_data.py.

Polite: one request at a time, 2 s apart (the site's robots.txt Crawl-delay), cached pages are never
fetched again. Delete pdb_cache/ (or single files in it) to pick up site updates.

    python3 -I scrape_pdb.py
"""
import gzip
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

BASE = "https://pokemondb.net/"
CACHE = pathlib.Path(__file__).resolve().parent / "pdb_cache"
DELAY = 2.0
UA = "Mozilla/5.0 (X11; Linux x86_64) dexsearch-gen/1.0"
INDEX_PAGES = ["pokedex/all", "move/all", "ability"]

_last = 0.0


def cached(path):
    return CACHE / (path.replace("/", "_") + ".html.gz")


def fetch(path):
    """Page HTML from cache, else downloaded (and cached). Raises after 3 failed tries."""
    global _last
    f = cached(path)
    if f.exists():
        return gzip.decompress(f.read_bytes()).decode("utf-8")
    for attempt in range(3):
        time.sleep(max(0.0, _last + DELAY - time.monotonic()))
        _last = time.monotonic()
        try:
            req = urllib.request.Request(BASE + path, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                html = r.read()
            CACHE.mkdir(exist_ok=True)
            tmp = f.with_suffix(".tmp")
            tmp.write_bytes(gzip.compress(html, 6))
            tmp.replace(f)
            return html.decode("utf-8")
        except (urllib.error.URLError, TimeoutError) as e:
            print(f"{path}: {e}, retry {attempt + 1}", file=sys.stderr)
            time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"giving up on {path}")


def species_paths(all_html):
    """Unique /pokedex/<slug> links in national order from the pokedex/all table."""
    seen = []
    for slug in re.findall(r'class="ent-name" href="/(pokedex/[a-z0-9-]+)"', all_html):
        if slug not in seen:
            seen.append(slug)
    return seen


def main():
    pages = [fetch(p) for p in INDEX_PAGES]
    species = species_paths(pages[0])
    assert len(species) >= 1025, len(species)
    todo = [p for p in species if not cached(p).exists()]
    print(f"{len(species)} species, {len(todo)} to download, ~{len(todo) * DELAY / 60:.0f} min", flush=True)
    for i, p in enumerate(todo, 1):
        fetch(p)
        if i % 50 == 0:
            print(f"{i}/{len(todo)}", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
