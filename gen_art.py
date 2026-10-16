#!/usr/bin/env python3
"""Artwork for the "Pokémon artwork" setting and the shiny preview, from the PokeAPI sprites repo (game art ©
Nintendo / Game Freak).

assets/art/<num>.webp    official artwork, 256 px and lossy like assets/sprites (the HOME renders)
assets/pixel/<num>.webp  game sprite from its 96 px canvas, cropped to the Pokémon and squared, lossless; the app
                         draws it unfiltered
assets/shiny/<num>.webp        shiny HOME render, 256 px and lossy like assets/sprites
assets/shiny-art/<num>.webp    shiny official artwork, like assets/art
assets/shiny-pixel/<num>.webp  shiny game sprite, like assets/pixel

Numbers are those in assets/sprites, so run after gen_data.py. Files on disk are kept; a rerun fetches only what is
missing. The app shows the HOME render (shiny or not) for a Pokémon missing here.
"""
import io
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image

BASE = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/"
ASSETS = Path(__file__).parent / "assets"
PX = 256


def fetch(url):
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            return Image.open(io.BytesIO(r.read())).convert("RGBA")
    except urllib.error.HTTPError:
        return None


def render(sub, path, num):
    dst = ASSETS / sub / f"{num}.webp"
    if dst.exists():
        return True
    img = fetch(f"{BASE}{path}{num}.png")
    if img is None:
        return False
    img.thumbnail((PX, PX), Image.Resampling.LANCZOS)
    img.save(dst, "WEBP", quality=80, method=6)
    return True


def art(num):
    return render("art", "other/official-artwork/", num)


def shiny(num):
    return render("shiny", "other/home/shiny/", num)


def shiny_art(num):
    return render("shiny-art", "other/official-artwork/shiny/", num)


def pixel(num, sub="pixel", path=""):
    dst = ASSETS / sub / f"{num}.webp"
    if dst.exists():
        return True
    img = fetch(f"{BASE}{path}{num}.png")
    box = img.getchannel("A").getbbox() if img else None
    if box is None:
        return False
    img = img.crop(box)
    side = max(img.size)
    square = Image.new("RGBA", (side, side))
    square.paste(img, ((side - img.width) // 2, (side - img.height) // 2))
    square.save(dst, "WEBP", lossless=True, quality=100, method=6)
    return True


nums = sorted(int(p.stem) for p in (ASSETS / "sprites").glob("*.webp"))
keep = {f"{n}.webp" for n in nums}
with ThreadPoolExecutor(16) as pool:
    for sub, job in (("art", art), ("pixel", pixel), ("shiny", shiny), ("shiny-art", shiny_art),
                     ("shiny-pixel", lambda n: pixel(n, "shiny-pixel", "shiny/"))):
        (ASSETS / sub).mkdir(exist_ok=True)
        done = list(pool.map(job, nums))
        missing = [n for n, ok in zip(nums, done) if not ok]
        print(f"{sub}: {len(nums) - len(missing)}/{len(nums)}, missing:", " ".join(map(str, missing)))
        for f in (ASSETS / sub).glob("*.webp"):
            if f.name not in keep:
                f.unlink()
assert Image.open(ASSETS / "art" / "25.webp").width == PX and (ASSETS / "pixel" / "25.webp").exists()
assert Image.open(ASSETS / "shiny" / "25.webp").width == PX and (ASSETS / "shiny-pixel" / "25.webp").exists()
print("art OK")
